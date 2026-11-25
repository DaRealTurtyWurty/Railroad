package dev.railroadide.railroad.ide;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.language.LanguageCapabilities;
import dev.railroadide.railroad.ide.language.LanguageSupports;
import dev.railroadide.railroad.ide.language.impl.JavaLanguageSupport;
import dev.railroadide.railroad.ide.language.impl.index.JavaLanguageIndexContextContributor;
import dev.railroadide.railroad.ide.language.index.ProjectIndexContext;
import dev.railroadide.railroad.ide.language.index.ProjectLanguageIndexService;
import dev.railroadide.railroad.ide.language.index.WorkspaceConfigurationParticipant;
import dev.railroadide.railroad.ide.language.jvm.JvmIndexContext;
import dev.railroadide.railroad.ide.language.jvm.JvmProjectModel;
import dev.railroadide.railroad.ide.language.jvm.JvmProjectResolver;
import dev.railroadide.railroad.ide.sst.document.api.DefaultDocumentStore;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import dev.railroadide.railroad.project.FacetDetectedEvent;
import dev.railroadide.railroad.project.RailroadProject;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

public class WorkspaceConfigurationParticipantTest {
    @TempDir
    public Path directory;

    @BeforeAll
    public static void initialize() {
        WorkspaceModes.initialize();
        LanguageSupports.initialize();
    }

    @BeforeEach
    public void normalizeProjectDirectory() throws Exception {
        directory = directory.toRealPath();
    }

    @Test
    public void participantsPrepareInOrderAndAdoptThePublishedConfiguration() throws Exception {
        var project = new RailroadProject(directory, "Participants", new WritableImage(1, 1));
        var service = Services.PROJECT_LANGUAGE_INDEX_SERVICE;
        ProjectLanguageIndexService.Session session = service.openProject(project);
        var store = new DefaultDocumentStore(project.getId(), ProjectWorkspaceConfiguration.initial(project));
        List<String> events = new CopyOnWriteArrayList<>();
        var first = new RecordingParticipant("first", events);
        var second = new RecordingParticipant("second", events);
        try (var tracker = new ProjectWorkspaceConfiguration(project, store, _ -> null, session,
            () -> List.of(first, second))) {
            tracker.ready().get(10, TimeUnit.SECONDS);
            WorkspaceConfigurationSnapshot published = store.capture().configuration();
            assertEquals(List.of("first:prepare:[]", "second:prepare:[index.test.first]", "first:adopt",
                "second:adopt"), events);
            assertSame(published, first.adopted);
            assertSame(published, second.adopted);
            assertEquals("done", published.settings().get("index.test.first"));
            assertEquals("done", published.settings().get("index.test.second"));
        } finally {
            service.closeProject(session);
        }
    }

    @Test
    public void unchangedInputsAreNotPreparedAgain() throws Exception {
        Files.writeString(directory.resolve("gradle.properties"), "A");
        var project = new RailroadProject(directory, "Unchanged", new WritableImage(1, 1));
        var service = Services.PROJECT_LANGUAGE_INDEX_SERVICE;
        ProjectLanguageIndexService.Session session = service.openProject(project);
        var store = new DefaultDocumentStore(project.getId(), ProjectWorkspaceConfiguration.initial(project));
        var participant = new RecordingParticipant("only", new CopyOnWriteArrayList<>());
        try (var tracker = new ProjectWorkspaceConfiguration(project, store, _ -> null, session,
            () -> List.of(participant))) {
            tracker.ready().get(10, TimeUnit.SECONDS);
            WorkspaceConfigurationSnapshot published = store.capture().configuration();
            Railroad.EVENT_BUS.publish(new FacetDetectedEvent(project, null));
            tracker.ready().get(10, TimeUnit.SECONDS);
            assertEquals(1, participant.prepares.get());
            assertSame(published, store.capture().configuration());

            Files.writeString(directory.resolve("gradle.properties"), "B");
            Railroad.EVENT_BUS.publish(new FacetDetectedEvent(project, null));
            WorkspaceConfigurationSnapshot changed = awaitPublished(tracker, store,
                configuration -> "B".equals(configuration.settings().get("project.build.gradle.properties")));
            assertTrue(participant.prepares.get() > 1);
            assertEquals("done", changed.settings().get("index.test.only"));
        } finally {
            service.closeProject(session);
        }
    }

    @Test
    public void javaTakesPartThroughItsLanguageSession() {
        var project = new RailroadProject(directory, "Java participant", new WritableImage(1, 1));
        assertFalse(WorkspaceConfigurationParticipant.of(project).isEmpty());
        assertTrue(new JavaLanguageSupport().supportedCapabilities()
            .contains(LanguageCapabilities.WORKSPACE_CONFIGURATION));
    }

    @Test
    public void gradleSettingsAreTakenFromAnyJvmLanguageContext() {
        var project = new RailroadProject(directory, "Gradle settings", new WritableImage(1, 1));
        Path jar = directory.resolve("groovy-only.jar");
        var gradle = new WorkspaceConfigurationSnapshot(0, Set.of(":app"), Map.of("module.:app.classpath",
            jar.toString()));
        var groovy = new JvmIndexContext("groovy", new JvmProjectModel(List.of(), List.of(), List.of(), List.of(),
            List.of(), null, gradle));
        var context = new ProjectIndexContext(project, directory, Map.of("groovy", groovy));

        WorkspaceConfigurationSnapshot initial = ProjectWorkspaceConfiguration.initial(context);
        assertEquals(Set.of(":app"), initial.moduleIds());
        assertEquals(jar.toString(), initial.settings().get("module.:app.classpath"));
    }

    @Test
    public void javaIndexContextWrapsTheSharedProjectModel() throws Exception {
        Path jar = Files.createDirectories(directory.resolve("libs")).resolve("library.jar");
        Files.write(jar, new byte[0]);
        var project = new RailroadProject(directory, "Plain", new WritableImage(1, 1));

        JvmProjectModel model = JvmProjectResolver.resolve(project);
        JvmIndexContext java = new JavaLanguageIndexContextContributor().resolve(project);

        assertTrue(model.classpathRoots().stream().anyMatch(path -> path.endsWith("library.jar")));
        assertEquals("java", java.languageId());
        assertEquals(model, java.model());
        assertEquals(model.sourceRoots(), java.sourceRoots());
    }

    /**
     * The project's file watcher may start its own refresh after a build file changes, superseding the request this
     * test started, so readiness is awaited until the expected configuration is published.
     */
    private static WorkspaceConfigurationSnapshot awaitPublished(
        ProjectWorkspaceConfiguration tracker,
        DefaultDocumentStore store,
        Predicate<WorkspaceConfigurationSnapshot> expected
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                tracker.ready().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | CancellationException superseded) {
                if (System.nanoTime() > deadline)
                    throw superseded;
            }

            WorkspaceConfigurationSnapshot current = store.capture().configuration();
            if (expected.test(current))
                return current;

            if (System.nanoTime() > deadline) {
                fail("Expected configuration was not published: " + current.settings());
            }

            Thread.onSpinWait();
        }
    }

    private static final class RecordingParticipant implements WorkspaceConfigurationParticipant {
        private final String name;
        private final List<String> events;
        private final AtomicInteger prepares = new AtomicInteger();
        private volatile WorkspaceConfigurationSnapshot adopted;

        private RecordingParticipant(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public WorkspaceConfigurationSnapshot prepare(
            ProjectLanguageIndexService.Session session,
            WorkspaceConfigurationSnapshot configuration
        ) {
            prepares.incrementAndGet();
            events.add(name + ":prepare:" + configuration.settings().keySet().stream()
                .filter(key -> key.startsWith("index.test.")).sorted().toList());
            Map<String, String> values = new TreeMap<>(configuration.settings());
            values.put("index.test." + name, "done");
            return new WorkspaceConfigurationSnapshot(configuration.revision(), configuration.moduleIds(), values);
        }

        @Override
        public void adopt(ProjectLanguageIndexService.Session session, WorkspaceConfigurationSnapshot configuration) {
            adopted = configuration;
            events.add(name + ":adopt");
        }

        @Override
        public void release(ProjectLanguageIndexService.Session session) {
        }
    }
}
