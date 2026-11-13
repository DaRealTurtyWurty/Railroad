package dev.railroadide.railroad.ide.diagnostics;

import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.diagnostics.EditorDiagnostic.TextEditorDiagnostic;
import dev.railroadide.railroad.ide.language.BaseLanguageSupport;
import dev.railroadide.railroad.ide.language.EditorOpenView;
import dev.railroadide.railroad.ide.language.LanguageFeatureFactory;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.language.index.ProjectIndexContext;
import dev.railroadide.railroad.ide.language.index.ProjectLanguageIndexService;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.document.api.DocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.Location.TextLocation;
import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import dev.railroadide.railroad.ide.sst.request.api.CancellationTokenSource;
import dev.railroadide.railroad.ide.sst.request.api.Deadline;
import dev.railroadide.railroad.ide.sst.request.api.DeadlineExceededException;
import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import dev.railroadide.railroad.ide.sst.request.api.FeatureResult;
import dev.railroadide.railroad.ide.sst.request.api.RequestPriority;
import dev.railroadide.railroad.ide.sst.request.api.SstScheduler;
import dev.railroadide.railroad.ide.sst.request.api.WorkspaceRequestContext;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ProjectDiagnosticsScannerTest {
    @TempDir
    public Path directory;

    @BeforeAll
    public static void initialize() {
        WorkspaceModes.initialize();
    }

    @Test
    public void failedProviderIsReportedAsIncompleteWhileHealthyFilesStillProduceDiagnostics() throws Exception {
        Files.writeString(directory.resolve("Broken.scheduling"), "broken");
        Files.writeString(directory.resolve("Healthy.scheduling"), "healthy");
        var active = new AtomicInteger();
        var calls = new AtomicInteger();
        var provider = new DiagnosticsProvider<TextEditorDiagnostic>() {
            @Override
            public List<TextEditorDiagnostic> compute(DocumentSnapshot snapshot) {
                throw new AssertionError("Scan must pass request controls");
            }

            @Override
            public FeatureResult<List<TextEditorDiagnostic>> compute(FeatureRequestContext context) {
                assertTrue(active.incrementAndGet() <= 2, "Scan must bound outstanding provider work");
                try {
                    calls.incrementAndGet();
                    context.checkActive();
                    assertTrue(context.deadline().remaining().isPresent());
                    var snapshot = (TextDocumentSnapshot) context.documentSnapshot();
                    if (snapshot.text().equals("broken"))
                        throw new DeadlineExceededException();
                    return new FeatureResult<>(context, List.of(new TextEditorDiagnostic(
                        TextLocation.from(snapshot, 0, 1), Diagnostic.Kind.WARNING, "HEALTHY", "healthy diagnostic")));
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        var support = new BaseLanguageSupport("scheduler-scan-test", "Scheduler scan", Set.of("scheduling")) {
            @Override
            public EditorOpenView open(Project project, DocumentId documentId, Path file) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LanguageFeatureFactory<DiagnosticsProvider> diagnosticsFactory() {
                return (_, _) -> provider;
            }
        };
        LanguageSupportRegistry.register(support);
        try {
            Project project = project();
            var context = new ProjectDiagnosticsContext(project,
                new ProjectIndexContext(project, directory, Map.of()), null);
            Path report = directory.resolve("reports/result.txt");
            assertEquals(report, ProjectDiagnosticsScanner.scan(context, report));
            String rendered = Files.readString(report);
            assertEquals(2, calls.get());
            assertTrue(rendered.contains("Scanned files: 2"));
            assertTrue(rendered.contains("Failed files: 1"));
            assertTrue(rendered.contains("Incomplete analysis:"));
            assertTrue(rendered.contains("Broken.scheduling: DeadlineExceededException"));
            assertTrue(rendered.contains("HEALTHY: healthy diagnostic"));
        } finally {
            LanguageSupportRegistry.REGISTRY.unregister(support.languageId());
        }
    }

    @Test
    public void cancelledScanDoesNotPublishEvenAnEmptyReport() {
        Project project = project();
        var cancellation = new CancellationTokenSource();
        cancellation.cancel();
        var context = new ProjectDiagnosticsContext(project,
            new ProjectIndexContext(project, directory, Map.of(), null, 0, cancellation.token()), null);
        Path report = directory.resolve("reports/cancelled.txt");
        assertThrows(CancellationException.class, () -> ProjectDiagnosticsScanner.scan(context, report));
        assertFalse(Files.exists(report));
    }

    @Test
    public void cancellingAnActiveScanDoesNotWaitForAnUncooperativeProvider() throws Exception {
        assertScanStopsWhileProviderIsBlocked(false, ScanInvalidation.CANCELLATION);
    }

    @Test
    public void cancellingAnInlineScanDoesNotWaitForAnUncooperativeProvider() throws Exception {
        assertScanStopsWhileProviderIsBlocked(true, ScanInvalidation.CANCELLATION);
    }

    @Test
    public void closingTheProjectStopsAnActiveScanWhileItsProviderIsBlocked() throws Exception {
        assertScanStopsWhileProviderIsBlocked(false, ScanInvalidation.PROJECT_CLOSURE);
    }

    @Test
    public void closingTheProjectStopsAnInlineScanWhileItsProviderIsBlocked() throws Exception {
        assertScanStopsWhileProviderIsBlocked(true, ScanInvalidation.PROJECT_CLOSURE);
    }

    @Test
    public void refreshingTheProjectStopsAnActiveScanWhileItsProviderIsBlocked() throws Exception {
        assertScanStopsWhileProviderIsBlocked(false, ScanInvalidation.CONFIGURATION_CHANGE);
    }

    @Test
    public void refreshingTheProjectStopsAnInlineScanWhileItsProviderIsBlocked() throws Exception {
        assertScanStopsWhileProviderIsBlocked(true, ScanInvalidation.CONFIGURATION_CHANGE);
    }

    private void assertScanStopsWhileProviderIsBlocked(boolean inline, ScanInvalidation invalidation) throws Exception {
        Files.writeString(directory.resolve("Blocked.scheduling"), "blocked");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var observed = new AtomicReference<FeatureRequestContext>();
        var provider = new DiagnosticsProvider<TextEditorDiagnostic>() {
            @Override
            public List<TextEditorDiagnostic> compute(DocumentSnapshot snapshot) {
                throw new AssertionError("Scan must pass request controls");
            }

            @Override
            public FeatureResult<List<TextEditorDiagnostic>> compute(FeatureRequestContext context) {
                observed.set(context);
                entered.countDown();
                try {
                    while (true) {
                        try {
                            release.await();
                            return new FeatureResult<>(context, List.of());
                        } catch (InterruptedException _) {
                            // Simulate plugin code that keeps running until the test releases it.
                        }
                    }
                } finally {
                    exited.countDown();
                }
            }
        };
        var support = new BaseLanguageSupport("scheduler-cancel-scan-test", "Cancelled scan", Set.of("scheduling")) {
            @Override
            public EditorOpenView open(Project project, DocumentId documentId, Path file) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LanguageFeatureFactory<DiagnosticsProvider> diagnosticsFactory() {
                return (_, _) -> provider;
            }
        };
        LanguageSupportRegistry.register(support);
        var outcome = new CompletableFuture<Throwable>();
        var cancellation = new CancellationTokenSource();
        Project project = project();
        var service = new ProjectLanguageIndexService();
        var session = invalidation == ScanInvalidation.CANCELLATION ? null : service.openProject(project);
        var context = new ProjectDiagnosticsContext(project,
            new ProjectIndexContext(project, directory, Map.of(), session,
                session == null ? 0 : session.configurationGeneration(), cancellation.token()),
            null);
        Path report = directory.resolve("reports/cancelled-active.txt");
        Runnable scan = () -> {
            try {
                assertEquals(inline, SstScheduler.shared().isWorkerThread());
                ProjectDiagnosticsScanner.scan(context, report);
                outcome.complete(null);
            } catch (Throwable failure) {
                outcome.complete(failure);
            }
        };
        Thread caller = inline ? null : Thread.ofVirtual().unstarted(scan);
        try (var scope = SstScheduler.shared().newScope()) {
            if (inline) {
                var scheduledCancellation = new CancellationTokenSource();
                var workspace = new WorkspaceSnapshot(project.getId(), Map.of(),
                    new WorkspaceConfigurationSnapshot(0, Set.of()), 0);
                var request = new WorkspaceRequestContext(workspace, scheduledCancellation.token(), Deadline.none());
                scope.submit("scan", RequestPriority.BACKGROUND_INDEXING, request, scheduledCancellation, () -> {
                    scan.run();
                    return null;
                });
            } else {
                caller.start();
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                switch (invalidation) {
                    case CANCELLATION -> cancellation.cancel();
                    case PROJECT_CLOSURE -> service.closeProject(session);
                    case CONFIGURATION_CHANGE -> service.refreshProject(session);
                }
                assertInstanceOf(CancellationException.class, outcome.get(5, TimeUnit.SECONDS));
                assertEquals(1, release.getCount());
                assertTrue(observed.get().cancellationToken().isCancellationRequested());
                assertThrows(CancellationException.class, observed.get()::checkActive);
                assertFalse(Files.exists(report));
            } finally {
                release.countDown();
                cancellation.cancel();
                assertTrue(exited.await(5, TimeUnit.SECONDS));
                if (caller != null) {
                    caller.join(5000);
                }
                scope.completion().get(5, TimeUnit.SECONDS);
            }
        } finally {
            service.closeProject(session);
            LanguageSupportRegistry.REGISTRY.unregister(support.languageId());
        }
    }

    @Test
    public void cachedDiagnosticsFromAnEarlierScanAreReportedAsIncomplete() throws Exception {
        Path source = directory.resolve("Cached.scheduling");
        Files.writeString(source, "broken");
        var cached = new AtomicReference<FeatureResult<List<TextEditorDiagnostic>>>();
        var calls = new AtomicInteger();
        var provider = new DiagnosticsProvider<TextEditorDiagnostic>() {
            @Override
            public List<TextEditorDiagnostic> compute(DocumentSnapshot snapshot) {
                throw new AssertionError("Scan must pass request controls");
            }

            @Override
            public FeatureResult<List<TextEditorDiagnostic>> compute(FeatureRequestContext context) {
                calls.incrementAndGet();
                var snapshot = (TextDocumentSnapshot) context.documentSnapshot();
                if (cached.get() == null) {
                    assertEquals("broken", snapshot.text());
                    cached.set(new FeatureResult<>(context, List.of(new TextEditorDiagnostic(
                        TextLocation.from(snapshot, 0, 1), Diagnostic.Kind.ERROR, "STALE", "earlier diagnostic"))));
                    assertTrue(cached.get().belongsTo(context));
                } else {
                    assertEquals("healthy", snapshot.text());
                    assertFalse(cached.get().belongsTo(context));
                }
                return cached.get();
            }
        };
        var support = new BaseLanguageSupport("scheduler-stale-scan-test", "Stale scan", Set.of("scheduling")) {
            @Override
            public EditorOpenView open(Project project, DocumentId documentId, Path file) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LanguageFeatureFactory<DiagnosticsProvider> diagnosticsFactory() {
                return (_, _) -> provider;
            }
        };
        LanguageSupportRegistry.register(support);
        try {
            Project project = project();
            var context = new ProjectDiagnosticsContext(project,
                new ProjectIndexContext(project, directory, Map.of()), null);
            Path report = directory.resolve("reports/cached.txt");
            ProjectDiagnosticsScanner.scan(context, report);
            String firstReport = Files.readString(report);
            assertTrue(firstReport.contains("Failed files: 0"));
            assertTrue(firstReport.contains("STALE: earlier diagnostic"));

            Files.writeString(source, "healthy");
            ProjectDiagnosticsScanner.scan(context, report);
            String secondReport = Files.readString(report);
            assertEquals(2, calls.get());
            assertTrue(secondReport.contains("Failed files: 1"));
            assertTrue(secondReport.contains("Incomplete analysis:"));
            assertTrue(secondReport.contains("Cached.scheduling: IllegalStateException"));
            assertTrue(secondReport.contains("Diagnostics: 0"));
            assertFalse(secondReport.contains("STALE"));
        } finally {
            LanguageSupportRegistry.REGISTRY.unregister(support.languageId());
        }
    }

    private enum ScanInvalidation {
        CANCELLATION,
        PROJECT_CLOSURE,
        CONFIGURATION_CHANGE
    }

    private Project project() {
        return (Project) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Project.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getPath" -> directory;
                case "getId" -> directory.toString();
                case "getAlias" -> "Scanner test";
                case "getFacets" -> List.of();
                case "hasFacet" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "ScannerTestProject";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
