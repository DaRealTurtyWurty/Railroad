package dev.railroadide.railroad.ide.language.impl.groovy;

import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.language.index.ProjectIndexContext;
import dev.railroadide.railroad.ide.language.index.ProjectTypeIndex;
import dev.railroadide.railroad.ide.language.index.ProjectTypeIndex.ProjectType;
import dev.railroadide.railroad.ide.language.jvm.JvmIndexContext;
import dev.railroadide.railroad.project.RailroadProject;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class GroovyProjectLanguageIndexerTest {
    private static final String SHAPES = """
        package demo

        @interface Marker {}

        interface Shape {
            double area()
        }

        trait Named {
            String name
        }

        enum Colour { RED, GREEN }

        class Circle implements Shape, Named {
            double radius
            double area() { Math.PI * radius * radius }

            static class Builder {}

            def listener = new Runnable() { void run() {} }
        }
        """;

    @TempDir
    public Path directory;
    private final GroovyProjectLanguageIndexer indexer = new GroovyProjectLanguageIndexer();

    @BeforeAll
    public static void initialize() {
        WorkspaceModes.initialize();
    }

    @BeforeEach
    public void normalizeDirectory() throws Exception {
        directory = directory.toRealPath();
    }

    @Test
    public void indexesEveryDeclaredTypeWithItsKindAndNameOffset() {
        Path file = directory.resolve("src/main/groovy/demo/Shapes.groovy");
        List<ProjectType> types = indexer.indexFile(context(), file, SHAPES).types().stream()
            .sorted(Comparator.comparing(ProjectType::qualifiedName)).toList();

        assertEquals(List.of("demo.Circle", "demo.Circle.Builder", "demo.Colour", "demo.Marker", "demo.Named",
            "demo.Shape"), types.stream().map(ProjectType::qualifiedName).toList());
        assertEquals(List.of(ProjectTypeIndex.Kind.CLASS, ProjectTypeIndex.Kind.CLASS, ProjectTypeIndex.Kind.ENUM,
            ProjectTypeIndex.Kind.ANNOTATION, ProjectTypeIndex.Kind.TRAIT, ProjectTypeIndex.Kind.INTERFACE),
            types.stream().map(ProjectType::kind).toList());
        ProjectType circle = types.getFirst();
        assertEquals("Circle", circle.simpleName());
        assertEquals(SHAPES.indexOf("Circle implements"), circle.nameOffset());
        assertEquals(file, circle.file());
    }

    @Test
    public void scriptsAndUnparseableFilesDeclareNoTypes() {
        Path file = directory.resolve("build.groovy");

        assertEquals(List.of(), indexer.indexFile(context(), file, "println 'hello'\n").types());
        assertEquals(List.of(), indexer.indexFile(context(), file, "class Broken {\n").types());
    }

    @Test
    public void buildsFromGroovyFilesOnlyAndUpdatesIncrementally() throws Exception {
        Path groovy = write("src/main/groovy/demo/Circle.groovy", "package demo\nclass Circle {}\n");
        Path java = write("src/main/java/demo/Square.java", "package demo; class Square {}");

        GroovyProjectIndex index = indexer.build(context(), List.of(groovy, java));
        assertEquals(List.of("demo.Circle"), names(index));

        Path other = directory.resolve("src/main/groovy/demo/Square.groovy");
        index = indexer.withUpdatedFile(index, other,
            indexer.indexFile(context(), other, "package demo\nclass Square {}\n"));
        assertEquals(List.of("demo.Circle", "demo.Square"), names(index));

        index = indexer.withRemovedFile(index, groovy);
        assertEquals(List.of("demo.Square"), names(index));
        assertTrue(indexer.supports(other));
        assertFalse(indexer.supports(java));
    }

    @Test
    public void plainProjectsIncludeGroovySourceFolders() {
        var project = new RailroadProject(directory, "Plain Groovy", new WritableImage(1, 1));
        JvmIndexContext context = new GroovyLanguageIndexContextContributor().resolve(project);

        assertEquals("groovy", context.languageId());
        assertTrue(context.sourceRoots().contains(directory.resolve("src/main/groovy")), context.toString());
        assertTrue(context.sourceRoots().contains(directory.resolve("src/test/groovy")), context.toString());
    }

    private static List<String> names(GroovyProjectIndex index) {
        return index.types(() -> {
        }).stream().map(ProjectType::qualifiedName).sorted().toList();
    }

    private Path write(String path, String text) throws Exception {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private ProjectIndexContext context() {
        var project = new RailroadProject(directory, "Groovy index", new WritableImage(1, 1));
        return new ProjectIndexContext(project, directory, Map.of());
    }
}
