package dev.railroadide.railroad.ide.language.index;

import dev.railroadide.railroad.ide.language.LanguageSupport;
import dev.railroadide.railroad.ide.language.impl.JavaLanguageSupport;
import dev.railroadide.railroad.ide.sst.project.JavaProjectSemanticIndex;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import dev.railroadide.railroad.ide.sst.request.api.CancellationTokenSource;
import dev.railroadide.railroad.ide.sst.request.api.Deadline;
import dev.railroadide.railroad.ide.sst.request.api.WorkspaceRequestContext;
import dev.railroadide.railroad.ide.sst.provider.api.ProviderExecutor;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.language.LanguageSupports;

import static org.junit.jupiter.api.Assertions.*;

public class ProjectLanguageIndexCoordinatorTest {

    @TempDir
    public Path tempDir;

    @Test
    public void warmIndexesBuildsRegisteredLanguageIndexes() throws Exception {
        Path root = createProject("""
            package demo;

            class A {
            }
            """);

        ProjectLanguageIndexService indexService = createIndexService();
        ProjectIndexContext context = createContext(root, indexService);
        var coordinator = new ProjectLanguageIndexCoordinator(
            context, indexService, List.of(sourceSupport()));

        coordinator.warmIndexes();

        JavaProjectSemanticIndex index = indexService.currentTyped(context, JavaLanguageSupport.LANGUAGE_ID);
        assertNotNull(index);
        assertEquals(1, index.lookupQualifiedName("demo.A").size());
    }

    @Test
    public void handleFileChangeUpdatesAndRemovesIndexedFiles() throws Exception {
        Path root = createProject("""
            package demo;

            class A {
            }
            """);
        Path aFile = root.resolve("src/main/java/demo/A.java");

        ProjectLanguageIndexService indexService = createIndexService();
        ProjectIndexContext context = createContext(root, indexService);
        var coordinator = new ProjectLanguageIndexCoordinator(
            context, indexService, List.of(sourceSupport()));
        coordinator.warmIndexes();

        Files.writeString(aFile, """
            package demo;

            class A {
                static int VALUE;
            }
            """);

        coordinator.handleFileChange(aFile, StandardWatchEventKinds.ENTRY_MODIFY);

        JavaProjectSemanticIndex updated = indexService.currentTyped(context, JavaLanguageSupport.LANGUAGE_ID);
        assertNotNull(updated);
        assertEquals(1, updated.lookupMember("demo.A", "VALUE").size());

        coordinator.handleFileChange(aFile, StandardWatchEventKinds.ENTRY_DELETE);

        JavaProjectSemanticIndex removed = indexService.currentTyped(context, JavaLanguageSupport.LANGUAGE_ID);
        assertNotNull(removed);
        assertTrue(removed.lookupQualifiedName("demo.A").isEmpty());
    }

    @Test
    public void cancelledWarmupCannotPublishAfterProviderClearsInterruption() throws Exception {
        assertLateWarmupRejected(false);
    }

    @Test
    public void expiredWarmupCannotPublishAndDoesNotPoisonLaterRequests() throws Exception {
        assertLateWarmupRejected(true);
    }

    @Test
    public void aNoncooperativeLanguageTimesOutWithoutBlockingHealthyLanguageIndexes() throws Exception {
        Path root = createProject("package demo; class A {}");
        LanguageSupports.initialize();
        var service = new ProjectLanguageIndexService();
        ProjectIndexContext base = createContext(root, service);
        var release = new CompletableFuture<Void>();
        var entered = new CountDownLatch(1);
        var healthy = new AtomicBoolean();
        ProjectLanguageIndexer<?, ?> badIndexer = fakeIndexer("bad", () -> {
            entered.countDown();
            release.join();
            Thread.interrupted();
        });
        ProjectLanguageIndexer<?, ?> healthyIndexer = fakeIndexer("healthy", () -> healthy.set(true));
        service.registerIndexer(badIndexer);
        service.registerIndexer(healthyIndexer);
        var context = new ProjectIndexContext(base.project(), root,
            Map.of("bad", emptyLanguage("bad"), "healthy", emptyLanguage("healthy")), base.session());
        var workspace = new WorkspaceSnapshot("provider-isolation", Map.of(),
            new WorkspaceConfigurationSnapshot(0, Set.of()), 0);
        var cancellation = new CancellationTokenSource();
        var request = new WorkspaceRequestContext(workspace, cancellation.token(), Deadline.none());
        try (var providers = new ProviderExecutor(4, 2, 8, Duration.ofMillis(100))) {
            var coordinator = new ProjectLanguageIndexCoordinator(context, service,
                List.of(fakeSupport(badIndexer), fakeSupport(healthyIndexer)), providers, Duration.ofSeconds(1));
            coordinator.warmIndexes(request);
            assertEquals(0, entered.getCount());
            assertTrue(healthy.get());
            assertNull(service.current(context, "bad"));
            assertNotNull(service.current(context, "healthy"));
            assertTrue(providers.health().stream().anyMatch(item -> item.providerId().contains(":bad:")
                && item.timedOut() == 1));
        } finally {
            service.closeProject(base.session());
            release.complete(null);
        }
    }

    private static ProjectLanguageIndexer<?, ?> fakeIndexer(String id, Runnable build) {
        return (ProjectLanguageIndexer<?, ?>) Proxy.newProxyInstance(ProjectLanguageIndexer.class.getClassLoader(),
            new Class<?>[]{ProjectLanguageIndexer.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "languageId" -> id;
                case "supports" -> true;
                case "build" -> {
                    build.run();
                    yield new TestIndex(id);
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static LanguageSupport fakeSupport(ProjectLanguageIndexer<?, ?> indexer) {
        return (LanguageSupport) Proxy.newProxyInstance(LanguageSupport.class.getClassLoader(),
            new Class<?>[]{LanguageSupport.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "languageId" -> indexer.languageId();
                case "createIndexer" -> indexer;
                case "warmAdditionalIndexes" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static LanguageIndexContext emptyLanguage(String id) {
        return (LanguageIndexContext) Proxy.newProxyInstance(LanguageIndexContext.class.getClassLoader(),
            new Class<?>[]{LanguageIndexContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "languageId" -> id;
                case "sourceRoots", "generatedRoots", "dependencyRoots" -> List.of();
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private record TestIndex(String languageId) implements ProjectLanguageIndex<LanguageFileIndex> {
        @Override
        public LanguageFileIndex getFileIndex(Path path) {
            return null;
        }
    }

    private void assertLateWarmupRejected(boolean expire) throws Exception {
        Path root = createProject("package demo; class A {}");
        LanguageSupports.initialize();
        var delegate = new JavaLanguageSupport().createIndexer();
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var first = new AtomicBoolean(true);
        var indexer = (ProjectLanguageIndexer<?, ?>) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ProjectLanguageIndexer.class}, (proxy, method, arguments) -> {
                Object value = method.invoke(delegate, arguments);
                if (method.getName().equals("build") && first.compareAndSet(true, false)) {
                    entered.countDown();
                    release.join();
                    Thread.interrupted();
                }
                return value;
            });
        var service = new ProjectLanguageIndexService();
        service.registerIndexer(indexer);
        ProjectIndexContext context = createContext(root, service);
        var coordinator = new ProjectLanguageIndexCoordinator(context, service, List.of(sourceSupport()));
        var cancellation = new CancellationTokenSource();
        var nanos = new AtomicLong();
        Deadline deadline = expire ? Deadline.after(Duration.ofNanos(1), nanos::get) : Deadline.none();
        var workspace = new WorkspaceSnapshot("coordinator", Map.of(),
            new WorkspaceConfigurationSnapshot(0, Set.of()), 0);
        var request = new WorkspaceRequestContext(workspace, cancellation.token(), deadline);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> coordinator.warmIndexes(request));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                if (expire) {
                    nanos.set(1);
                } else {
                    cancellation.cancel();
                }
                release.complete(null);
                assertInstanceOf(CancellationException.class,
                    assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS)).getCause());
                assertNull(service.currentTyped(context, JavaLanguageSupport.LANGUAGE_ID));
                coordinator.warmIndexes();
                assertNotNull(service.currentTyped(context, JavaLanguageSupport.LANGUAGE_ID));
            } finally {
                release.complete(null);
                service.closeProject(context.session());
            }
        }
    }

    private ProjectLanguageIndexService createIndexService() {
        LanguageSupport support = new JavaLanguageSupport();
        LanguageSupports.initialize();
        var indexService = new ProjectLanguageIndexService();
        indexService.registerIndexer(support.createIndexer());
        indexService.registerPersistence(support.createPersistence());
        return indexService;
    }

    // This fixture tests coordinator source updates without bootstrapping global UI/binary services.
    private LanguageSupport sourceSupport() {
        var java = new JavaLanguageSupport();
        return (LanguageSupport) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{LanguageSupport.class}, (proxy, method, args) -> {
                if (method.getName().equals("warmAdditionalIndexes"))
                    return null;
                return method.invoke(java, args);
            });
    }

    private ProjectIndexContext createContext(Path root, ProjectLanguageIndexService service) {
        Project project = (Project) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Project.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getPath" -> root;
                case "getAlias" -> root.getFileName().toString();
                case "hasFacet" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "TestProject[" + root + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        var context = new DefaultProjectIndexContextResolver().resolve(project);
        return new ProjectIndexContext(project, root, context.languages(), service.openProject(project));
    }

    private Path createProject(String source) throws Exception {
        Path root = tempDir.resolve("project-" + System.nanoTime());
        Path file = root.resolve("src/main/java/demo/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return root;
    }
}
