package dev.railroadide.railroad.ide.ui.codeeditor;

import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.language.impl.groovy.GroovyEditorFeatures;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.request.api.CancellationToken;
import dev.railroadide.railroad.ide.sst.request.api.CancellationTokenSource;
import dev.railroadide.railroad.ide.ui.JavaEditorFeatures;
import dev.railroadide.railroad.project.RailroadProject;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CodeEditorFeaturesTest {
    @TempDir
    public Path directory;

    @BeforeAll
    public static void startFx() throws Exception {
        WorkspaceModes.initialize();
        var started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }

        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void featuresAreInstalledOnOpenAndRemovedOnceOnClose() throws Exception {
        Path file = Files.writeString(directory.resolve("example.test"), "first\nsecond\n");
        var installs = new AtomicInteger();
        var closes = new AtomicInteger();
        EditorFeatures features = new EditorFeatures() {
            @Override
            public AutoCloseable install(CodeEditorPane editor) {
                installs.incrementAndGet();
                editor.getGutter().addColumn(new GutterColumn("test-marker", 10, line -> {
                    if (line != 1)
                        return null;

                    var marker = new Label("*");
                    marker.getStyleClass().add("test-marker");
                    return marker;
                }));

                return () -> {
                    closes.incrementAndGet();
                    editor.getGutter().removeColumn("test-marker");
                };
            }
        };

        CodeEditorPane editor = onFx(() -> open(file, features, CancellationToken.none()));
        onFx(() -> {
            assertEquals(1, installs.get());
            assertTrue(hasMarker(editor, ".test-marker"));
            editor.close();
            editor.close();
            return null;
        });

        onFx(() -> {
            assertEquals(1, closes.get());
            assertFalse(hasMarker(editor, ".test-marker"));
            return null;
        });
    }

    @Test
    public void typingRulesFallBackToDefaultsOnceTheProviderLifetimeEnds() throws Exception {
        Path file = Files.writeString(directory.resolve("example.test"), "call(");
        EditorFeatures features = new EditorFeatures() {
            @Override
            public boolean shouldRequestSignatureHelp(
                String text,
                int caret,
                boolean textChanged,
                boolean helpShown
            ) {
                return true;
            }
        };

        var lifetime = new CancellationTokenSource();
        CodeEditorPane editor = onFx(() -> open(file, features, lifetime.token()));
        try {
            onFx(() -> {
                assertTrue(editor.shouldRequestSignatureHelp("call(", 5, false));
                return null;
            });

            lifetime.cancel();
            onFx(() -> {
                assertFalse(editor.shouldRequestSignatureHelp("call(", 5, false));
                assertTrue(editor.shouldRequestSignatureHelp("call(", 5, true));
                return null;
            });
        } finally {
            close(editor);
        }
    }

    @Test
    public void failingInstallStillOpensTheEditor() throws Exception {
        Path file = Files.writeString(directory.resolve("example.test"), "text");
        EditorFeatures features = new EditorFeatures() {
            @Override
            public AutoCloseable install(CodeEditorPane editor) {
                throw new IllegalStateException("broken plugin");
            }
        };

        CodeEditorPane editor = onFx(() -> open(file, features, CancellationToken.none()));
        try {
            onFx(() -> {
                assertEquals("text", editor.getText());
                return null;
            });
        } finally {
            close(editor);
        }
    }

    @Test
    public void javaFeaturesAddImportFoldingAndRemoveItOnClose() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"),
            "package example;\n\nimport java.util.List;\nimport java.util.Map;\n\nclass Example {}\n");
        CodeEditorPane editor = onFx(() -> open(file, new JavaEditorFeatures(), CancellationToken.none()));
        onFx(() -> {
            assertTrue(hasMarker(editor, ".editor-fold-marker"));
            editor.close();
            return null;
        });

        onFx(() -> {
            assertFalse(hasMarker(editor, ".editor-fold-marker"));
            return null;
        });
    }

    @Test
    public void groovyFeaturesShowBreakpointsUntilTheEditorCloses() throws Exception {
        Path file = Files.writeString(directory.resolve("Script.groovy"), "println 'one'\nprintln 'two'\n");
        CodeEditorPane editor = onFx(() -> open(file, new GroovyEditorFeatures(), CancellationToken.none()));
        try {
            onFx(() -> {
                Services.BREAKPOINT_SERVICE.toggle(file, 2);
                editor.getGutter().refresh();
                assertTrue(hasMarker(editor, ".editor-breakpoint-marker"));
                editor.close();
                return null;
            });

            onFx(() -> {
                assertFalse(hasMarker(editor, ".editor-breakpoint-marker"));
                return null;
            });
        } finally {
            onFx(() -> {
                Services.BREAKPOINT_SERVICE.get(file, 2).ifPresent(_ -> Services.BREAKPOINT_SERVICE.toggle(file, 2));
                return null;
            });
        }
    }

    @Test
    public void plainCodeEditorDoesNotFoldJavaImports() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"),
            "package example;\n\nimport java.util.List;\nimport java.util.Map;\n\nclass Example {}\n");
        CodeEditorPane editor = onFx(() -> open(file, null, CancellationToken.none()));
        try {
            onFx(() -> {
                assertFalse(hasMarker(editor, ".editor-fold-marker"));
                return null;
            });
        } finally {
            close(editor);
        }
    }

    @Test
    public void javaSignatureHelpIsRequestedOnlyInsideCallArguments() {
        var features = new JavaEditorFeatures();
        assertTrue(features.shouldRequestSignatureHelp("call(a,", 7, false, false));
        assertTrue(features.shouldRequestSignatureHelp("call(\"(\" ", 9, false, true));
        assertFalse(features.shouldRequestSignatureHelp("call(a) ", 8, true, false));
        assertFalse(features.shouldRequestSignatureHelp("if (a", 5, true, false));
        assertFalse(features.shouldRequestSignatureHelp("call(a ", 7, false, false));
    }

    private CodeEditorPane open(Path file, EditorFeatures features, CancellationToken lifetime) {
        var project = new RailroadProject(directory, "Editor features", new WritableImage(1, 1));
        return new CodeEditorPane(project, DocumentId.create(), file,
            new CodeEditorConfig("test", null, null, null, null, null, null, null, features, lifetime));
    }

    private static boolean hasMarker(CodeEditorPane editor, String selector) {
        for (int paragraph = 0; paragraph < editor.getParagraphs().size(); paragraph++) {
            Node graphic = editor.getParagraphGraphicFactory().apply(paragraph);
            if (graphic != null && graphic.lookup(selector) != null)
                return true;
        }

        return false;
    }

    private static void close(CodeEditorPane editor) throws Exception {
        onFx(() -> {
            editor.close();
            return null;
        });
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
