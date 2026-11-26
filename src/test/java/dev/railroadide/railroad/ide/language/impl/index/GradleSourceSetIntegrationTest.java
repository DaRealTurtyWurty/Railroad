package dev.railroadide.railroad.ide.language.impl.index;

import dev.railroadide.railroad.gradle.model.*;
import dev.railroadide.railroad.DefaultGradleEnvironment;
import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.language.impl.JavaLanguageSupport;
import dev.railroadide.railroad.ide.language.index.WorkspaceConfigurationParticipant;
import dev.railroadide.railroad.gradle.project.GradleManager;
import dev.railroadide.railroad.gradle.service.impl.ToolingGradleModelService;
import dev.railroadide.railroad.ide.ProjectDocumentSynchronizer;
import dev.railroadide.railroad.ide.ProjectWorkspaceConfiguration;
import dev.railroadide.railroad.ide.diagnostics.JavaDiagnosticsProvider;
import dev.railroadide.railroad.ide.sst.document.api.*;
import dev.railroadide.railroad.ide.sst.edit.api.WorkspaceChange;
import dev.railroadide.railroad.ide.sst.impl.java.*;
import dev.railroadide.railroad.ide.sst.project.JavaLibrarySources;
import dev.railroadide.railroad.ide.sst.project.JavaLibrarySymbolIndex;
import dev.railroadide.railroad.ide.sst.provider.api.*;
import dev.railroadide.railroad.ide.sst.request.api.*;
import dev.railroadide.railroad.project.RailroadProject;
import dev.railroadide.railroad.project.facet.Facet;
import dev.railroadide.railroad.project.facet.FacetManager;
import javafx.scene.image.WritableImage;
import org.gradle.tooling.BuildException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.language.LanguageSupports;
import dev.railroadide.railroad.gradle.GradleSettings;
import dev.railroadide.railroad.gradle.service.GradleModelService;
import java.lang.reflect.Field;
import dev.railroadide.railroad.ide.language.index.ProjectLanguageIndexService;
import static org.junit.jupiter.api.Assertions.*;

public class GradleSourceSetIntegrationTest {
    @TempDir
    public Path directory;
    @TempDir
    public Path modelCache;
    private final DocumentIdentityRegistry identities = new DocumentIdentityRegistry();

    @Test
    public void editorMatchesGradleBeforeAndAfterChangingClientDependencies() throws Exception {
        WorkspaceModes.initialize();
        LanguageSupports.initialize();
        directory = directory.toRealPath();
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'fixture'\ninclude 'library'");
        Path build = directory.resolve("build.gradle");
        String originalBuild = """
            plugins { id 'java' }
            defaultTasks 'shouldNeverRunOnSync'
            tasks.register('shouldNeverRunOnSync') { doLast { throw new GradleException('Ran default task on sync') } }
            sourceSets {
                client { java.srcDirs = ['client-code']; compileClasspath += main.output }
                independent
            }
            """;
        Files.writeString(build, originalBuild);
        TextDocumentSnapshot game = file("src/main/java/demo/Game.java",
            "package demo; public class Game { public static int value() { return 1; } }");
        TextDocumentSnapshot test = file("src/test/java/demo/OnlyInTests.java",
            "package demo; public class OnlyInTests { public int read() { return Game.value(); } }");
        TextDocumentSnapshot client = file("client-code/demo/Client.java",
            "package demo; public class Client { int read() { return Game.value() + ClientApi.answer(); } }");
        file("src/independent/java/demo/Game.java",
            "package demo; public class Game { public static int value() { return 2; } int read() { return value(); } }");
        TextDocumentSnapshot dependency = file("library/src/main/java/demo/ClientApi.java",
            "package demo; public class ClientApi { public static int answer() { return 3; } }");
        file("extra-client/demo/ClientExtension.java", "package demo; public class ClientExtension {}");
        Files.writeString(directory.resolve("library/build.gradle"), "plugins { id 'java-library' }");
        var project = new RailroadProject(directory, "fixture", new WritableImage(1, 1));
        project.facetsProperty().add(new Facet<>(FacetManager.GRADLE));
        var store = new DefaultDocumentStore(project.getId(), ProjectWorkspaceConfiguration.initial(project));
        var gradleHome = new File(System.getProperty("railroad.test.gradleHome"));
        ProjectLanguageIndexService.Session session = Services.PROJECT_LANGUAGE_INDEX_SERVICE.openProject(project);
        DocumentId buildDocument = identities.identify(DocumentUri.fromPath(build)).id();
        try (var imports = Executors.newSingleThreadExecutor();
            ProjectWorkspaceConfiguration configuration = openWorkspace(project, store, gradleHome, imports);
            var watcher = new ProjectDocumentSynchronizer(directory, store, identities, buildDocument::equals,
                Runnable::run,
                configuration::fileChanged);
            ProjectConnection connection = GradleConnector.newConnector().forProjectDirectory(directory.toFile())
                .useInstallation(gradleHome).connect();
            var provider = new JavaWorkspaceAnalysisProvider(() -> true,
                (roots, _) -> JavaLibrarySymbolIndex.build(roots));
            var services = new JavaSstServices(JavaLibrarySources.shared(), provider::capture)) {
            GradleModelService models = project.getGradleManager().getGradleModelService();
            watcher.initialScan().get(30, TimeUnit.SECONDS);
            models.refreshModel(false).get(120, TimeUnit.SECONDS);
            WorkspaceSnapshot before = awaitClientSourceRoots(store, false);
            var diagnostics = new JavaDiagnosticsProvider(null, null, provider::capture);
            assertFalse(provider.capture(before, client.id()).typeNames().contains("ClientApi"));
            assertTrue(
                diagnostics.compute(before, client.id()).stream().anyMatch(d -> d.code().startsWith("SEM_UNRESOLVED")));
            connection.newBuild().forTasks(":compileTestJava", ":compileIndependentJava").run();
            assertThrows(BuildException.class, () -> connection.newBuild().forTasks("compileClientJava").run());

            assertFalse(provider.capture(before, client.id()).typeNames().contains("ClientExtension"));
            Files.writeString(build, originalBuild + """
                dependencies { clientImplementation project(':library') }
                sourceSets.client.java.srcDir('extra-client')
                """);
            WorkspaceSnapshot workspace = awaitClientSourceRoots(store, true);

            GradleBuildModel updated = models.getCachedModel().orElseThrow();
            var config = ProjectWorkspaceConfiguration.captureGradleConfiguration(updated);
            var liveAnalysis = JavaLanguageSupport.analysisContextProvider();
            assertTrue(offersAnswer(liveAnalysis.index(workspace, client.id())));
            assertTrue(provider.capture(workspace, client.id()).typeNames().contains("ClientExtension"));
            assertFalse(provider.capture(workspace, game.id()).typeNames().contains("ClientExtension"));
            assertFalse(Files.exists(directory.resolve("library/build/classes/java/main/demo/ClientApi.class")));
            assertTrue(offersAnswer(provider.capture(workspace, client.id())));
            assertEquals(dependency.id(),
                services.definitions(context(workspace, client), client.text().indexOf("answer"))
                    .value().getFirst().location().documentId());
            assertFalse(offersAnswer(provider.capture(workspace, game.id())));
            assertFalse(provider.capture(workspace, game.id()).typeNames().contains("ClientApi"));
            assertFalse(provider.capture(before, client.id()).typeNames().contains("ClientApi"));
            connection.newBuild().forTasks("compileClientJava").run();
            assertFalse(diagnostics.compute(workspace, client.id()).stream()
                .anyMatch(d -> d.code().startsWith("SEM_UNRESOLVED")));

            FeatureRequestContext clientContext = context(workspace, client);
            assertEquals(game.id(), services.definitions(clientContext, client.text().indexOf("value")).value()
                .getFirst().location().documentId());
            FeatureRequestContext gameContext = context(workspace, game);
            int offset = game.text().indexOf("value");
            UsageSearch usages = services.usages(gameContext, new UsageQuery.AtOffset(offset), UsageSink.NONE).value();
            assertEquals(Set.of(test.id(), client.id()),
                usages.usages().stream().map(Location::documentId).collect(Collectors.toSet()));
            RenameProposal rename = services.rename(gameContext, offset, usages.symbol(), "amount").value();
            assertTrue(rename.applicable(), () -> rename.conflicts().toString());
            assertEquals(Set.of(game.id(), test.id(), client.id()), rename.edit().changes().stream()
                .map(WorkspaceChange.TextEdit.class::cast).map(edit -> edit.expected().id())
                .collect(Collectors.toSet()));

            var cache = new PersistedGradleModelStore(directory.resolve("cache"), "test");
            assertTrue(cache.save(directory, updated, Instant.now()));
            PersistedGradleModelStore.Restored reopened = cache.load(directory).orElseThrow();
            assertTrue(reopened.current());
            var restored = ProjectWorkspaceConfiguration.captureGradleConfiguration(reopened.model());
            assertEquals(config, restored);
            var reopenedStore = new DefaultDocumentStore("reopened", restored);
            List.of(game, client, dependency).forEach(reopenedStore::publishBacking);
            assertTrue(offersAnswer(provider.capture(reopenedStore.capture(), client.id())));
            assertFalse(offersAnswer(provider.capture(reopenedStore.capture(), game.id())));

            TextDocumentSnapshot leak = file("src/main/java/demo/Leak.java",
                "package demo; class Leak { void run(OnlyInTests t) { t.read(); } }");
            watcher.refresh(leak.uri().filePath().orElseThrow()).get(10, TimeUnit.SECONDS);
            JavaWorkspaceAnalysis analysis = provider.capture(store.capture(), leak.id());
            var completion = JavaSemanticCompletionEngine.compute(leak.text(), leak.text().indexOf("t.read") + 1,
                analysis);
            assertTrue(
                completion == null || completion.items().stream().noneMatch(item -> item.insertText().equals("read")));
            assertTrue(diagnostics.compute(store.capture(), leak.id()).stream()
                .anyMatch(d -> d.code().equals("SEM_UNRESOLVED_TYPE")));
            FeatureRequestContext check = context(store.capture(), test);
            int readOffset = test.text().indexOf("read");
            UsageSearch read = services.usages(check, new UsageQuery.AtOffset(readOffset), UsageSink.NONE).value();
            assertTrue(read.usages().isEmpty());
            RenameProposal renameRead = services.rename(check, readOffset, read.symbol(), "inspect").value();
            assertTrue(renameRead.applicable(), () -> renameRead.conflicts().toString());
            assertEquals(List.of(test.id()),
                renameRead.edit().changes().stream().map(WorkspaceChange.TextEdit.class::cast)
                    .map(edit -> edit.expected().id()).toList());
            assertThrows(BuildException.class, () -> connection.newBuild().forTasks("compileJava").run());
            Files.writeString(build, originalBuild);
            WorkspaceSnapshot removed = awaitClientSourceRoots(store, false);
            JavaWorkspaceAnalysis afterRemoval = liveAnalysis.index(removed, client.id());
            assertFalse(offersAnswer(afterRemoval));
            assertFalse(afterRemoval.typeNames().contains("ClientExtension"));
        } finally {
            Services.PROJECT_LANGUAGE_INDEX_SERVICE.closeProject(session);
            WorkspaceConfigurationParticipant.of(project).forEach(participant -> participant.release(session));
            LanguageSupportRegistry.runtime().closeProject(project);
        }
    }

    private static WorkspaceSnapshot awaitClientSourceRoots(DocumentStore store, boolean hasExtraRoot)
        throws Exception {
        var imported = new CompletableFuture<WorkspaceSnapshot>();
        Runnable check = () -> {
            WorkspaceSnapshot latest = store.capture();
            String roots = latest.configuration().settings().get("module.:.set.client.sources");
            if (roots != null && roots.contains("extra-client") == hasExtraRoot) {
                imported.complete(latest);
            }
        };

        try (DocumentStore.ChangeSubscription subscription = store.onChange(check)) {
            check.run();
            return imported.get(120, TimeUnit.SECONDS);
        }
    }

    private ProjectWorkspaceConfiguration openWorkspace(
        RailroadProject project,
        DocumentStore store,
        File gradleHome,
        Executor imports
    ) throws ReflectiveOperationException {
        GradleSettings settings = project.getGradleManager().getGradleSettings();
        settings.setGradleJvm(null);
        var environment = new DefaultGradleEnvironment(project, gradleHome.toPath(), settings);
        var models = new ToolingGradleModelService(project, environment, imports,
            new PersistedGradleModelStore(modelCache, "fixture"));
        Field serviceField = GradleManager.class.getDeclaredField("modelService");
        serviceField.setAccessible(true);
        serviceField.set(project.getGradleManager(), models);
        return new ProjectWorkspaceConfiguration(project, store);
    }

    private static boolean offersAnswer(JavaWorkspaceAnalysis analysis) {
        String text = "package demo; class Example { void use() { ClientApi. } }";
        var result = JavaSemanticCompletionEngine.compute(text, text.indexOf("ClientApi.") + "ClientApi".length(),
            analysis);
        return result != null && result.items().stream().anyMatch(item -> item.insertText().equals("answer"));
    }

    private TextDocumentSnapshot file(String relative, String text) throws Exception {
        Path path = directory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        DocumentUri uri = DocumentUri.fromPath(path);
        return new TextDocumentSnapshot(identities.identify(uri).id(), uri, DocumentVersion.initial(),
            "java", text, StandardCharsets.UTF_8);
    }

    private static FeatureRequestContext context(WorkspaceSnapshot workspace, TextDocumentSnapshot document) {
        return new FeatureRequestContext(workspace, workspace.document(document.id()).orElseThrow(),
            CancellationToken.NONE, Deadline.none());
    }
}
