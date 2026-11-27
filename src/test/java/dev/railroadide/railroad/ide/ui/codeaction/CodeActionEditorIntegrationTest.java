package dev.railroadide.railroad.ide.ui.codeaction;

import com.panemu.tiwulfx.control.dock.DetachableTabPane;
import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.language.LanguageSupports;
import dev.railroadide.railroad.ide.sst.document.api.*;
import dev.railroadide.railroad.ide.sst.provider.api.CodeAction;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorPane;
import dev.railroadide.railroad.ide.ui.editor.EditorTab;
import dev.railroadide.railroad.ide.ui.editor.EditorTabManager;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.plugin.spi.events.ProjectEvent;
import dev.railroadide.railroad.project.RailroadProject;
import dev.railroadide.railroad.settings.keybinds.Keybinds;
import dev.railroadide.railroad.theme.ThemeManager;
import javafx.application.Platform;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class CodeActionEditorIntegrationTest {
    private static final String SOURCE = """
        package p;

        import java.util.Map;

        class A {
            ArrayList<String> names;
            List<String> ambiguous;
        }
        """;

    @TempDir
    public Path projectDirectory;
    private Project project;
    private Stage stage;
    private CodeEditorPane editor;
    private Path file;
    private DetachableTabPane tabs;

    @BeforeAll
    public static void startJavaFx() throws Exception {
        WorkspaceModes.initialize();
        var started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }

        assertTrue(started.await(10, TimeUnit.SECONDS));
        Field hostServices = Railroad.class.getDeclaredField("hostServices");
        hostServices.setAccessible(true);
        if (hostServices.get(null) == null) {
            hostServices.set(null, new Railroad().getHostServices());
        }
    }

    @BeforeEach
    public void openEditor() throws Exception {
        onFx(() -> {
            LanguageSupports.initialize();
            if (ThemeManager.getTheme() == null) {
                ThemeManager.setTheme("");
            }

            project = new RailroadProject(projectDirectory, "Code actions", new WritableImage(1, 1));
            Services.IDE_STATE.setCurrentProject(project);
            return null;
        });

        Services.IDE_STATE.getWorkspaceConfiguration().ready().get(10, TimeUnit.SECONDS);
        file = projectDirectory.resolve("src/main/java/p/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        tabs = onFx(DetachableTabPane::new);
        stage = onFx(() -> {
            Platform.setImplicitExit(false);
            ThemeManager.init();
            Keybinds.initialize();
            var window = new Stage();
            window.setScene(new Scene(tabs, 800, 600));
            window.show();
            return window;
        });

        editor = open(file);
    }

    @AfterEach
    public void closeProject() throws Exception {
        onFx(() -> {
            stage.close();
            Services.EDITOR_TAB_MANAGER.discardUnsavedChangesOnClose();
            Services.EDITOR_TAB_MANAGER.prepareProjectClose();
            Railroad.EVENT_BUS.publish(new ProjectEvent(project, ProjectEvent.EventType.CLOSED));
            return null;
        });

        assertTrue(Services.IDE_STATE.getWorkspaceEdits().isEmpty());
        assertNull(onFx(editor::activeCodeActionMenu), "Closing the project must close the code action menu");
    }

    @Test
    public void clickingTheLightBulbOpensQuickFixesWithoutMovingTheCaretOrSelection() throws Exception {
        int start = SOURCE.indexOf("ArrayList");
        int end = start + "ArrayList".length();
        onFx(() -> {
            editor.requestFocus();
            editor.selectRange(end, start);
            return null;
        });

        awaitFx(() -> {
            Node bulb = editor.lookup(".editor-code-action-bulb");
            if (bulb == null)
                return false;

            firePrimaryMouseEvent(bulb, MouseEvent.MOUSE_PRESSED);
            assertEquals(start, editor.getCaretPosition());
            assertEquals(end, editor.getAnchor());
            firePrimaryMouseEvent(bulb, MouseEvent.MOUSE_RELEASED);
            firePrimaryMouseEvent(bulb, MouseEvent.MOUSE_CLICKED);
            return true;
        });

        awaitFx(() -> editor.activeCodeActionMenu() != null);
        assertEquals(List.of("Import 'java.util.ArrayList'"), titles(onFx(editor::activeCodeActionMenu)));
        assertEquals(start, onFx(editor::getCaretPosition));
        assertEquals(end, onFx(editor::getAnchor));
    }

    @Test
    public void altEnterAddsAnImportThatUndoAndRedoAsOneStep() throws Exception {
        moveTo("ArrayList<");
        awaitFx(() -> editor.codeActionBulbLine() == 6);

        CodeActionMenu menu = openMenu();
        assertEquals(List.of("Import 'java.util.ArrayList'"), titles(menu));
        awaitFx(() -> !menu.previewLines().isEmpty());
        assertEquals(SOURCE, onFx(editor::getText), "Previewing must not change the document");

        pressInMenu(KeyCode.ENTER);
        String imported = SOURCE.replace("import java.util.Map;", "import java.util.ArrayList;\nimport java.util.Map;");
        awaitFx(() -> editor.getText().equals(imported));
        assertTrue(onFx(menu::isClosed));
        assertEquals(SOURCE, Files.readString(file), "Quick fixes edit the open buffer, not the saved file");

        onFx(() -> {
            editor.undo();
            return null;
        });

        assertEquals(SOURCE, onFx(editor::getText));
        onFx(() -> {
            editor.redo();
            return null;
        });

        assertEquals(imported, onFx(editor::getText));
    }

    @Test
    public void acceptingTypeCompletionBeforeADotImportsTheClassInOneUndoStep() throws Exception {
        String before = "package p;\n\nclass A { void run() {  } }\n";
        int start = before.indexOf("{  }") + 2;
        onFx(() -> {
            var uri = DocumentUri.fromPath(
                projectDirectory.resolve("src/main/java/net/minecraft/world/level/material/Fluids.java"));
            Services.IDE_STATE.getDocumentStore().orElseThrow().publishBacking(new TextDocumentSnapshot(
                DocumentId.create(), uri, DocumentVersion.initial(), "java", DocumentOrigin.original(uri),
                "package net.minecraft.world.level.material; public class Fluids {}", StandardCharsets.UTF_8));
            return null;
        });

        onFx(() -> {
            editor.replaceText(before);
            editor.requestFocus();
            editor.moveTo(start);
            for (char character : "Fluids".toCharArray()) {
                editor.insertText(editor.getCaretPosition(), String.valueOf(character));
            }

            return null;
        });

        String typed = before.substring(0, start) + "Fluids" + before.substring(start);
        assertEquals(typed, onFx(editor::getText));
        awaitFx(() -> visibleNode(".completion-popup") != null);
        awaitFx(() -> visibleNode(".completion-popup").lookup(".list-view") instanceof ListView<?> list
            && list.getSelectionModel().getSelectedItem() instanceof CompletionItem item
            && "Fluids".equals(item.insertText()) && "net.minecraft.world.level.material".equals(item.detail()));

        onFx(() -> {
            assertEquals(typed, editor.getText());
            press(Objects.requireNonNull(visibleNode(".completion-popup").lookup(".list-view")), KeyCode.ENTER, false);
            String expected = typed.replace("package p;",
                "package p;\n\nimport net.minecraft.world.level.material.Fluids;");
            assertEquals(expected, editor.getText());
            assertEquals(expected.lastIndexOf("Fluids") + "Fluids".length(), editor.getCaretPosition());
            editor.undo();
            assertEquals(typed, editor.getText());
            return null;
        });
    }

    @Test
    public void typingAndAcceptingLocalCompletionInsertsTheVariableName() throws Exception {
        String source = "class A { void run() { int localCount = 1; System.out.println(); } }\n";
        int start = source.indexOf("println(") + "println(".length();
        onFx(() -> {
            editor.replaceText(source);
            editor.requestFocus();
            editor.moveTo(start);
            editor.insertText(start, "l");
            return null;
        });

        awaitFx(() -> {
            Node popup = visibleNode(".completion-popup");
            return popup != null && popup.lookup(".list-view") instanceof ListView<?> list
                && list.getSelectionModel().getSelectedItem() instanceof CompletionItem item
                && "localCount".equals(item.insertText());
        });

        onFx(() -> {
            press(Objects.requireNonNull(visibleNode(".completion-popup").lookup(".list-view")), KeyCode.ENTER, false);
            assertEquals(source.replace("println()", "println(localCount)"), editor.getText());
            assertEquals(start + "localCount".length(), editor.getCaretPosition());
            return null;
        });
    }

    @Test
    public void completesAMethodWhileAnEarlierCallIsMissingItsClosingParenthesis() throws Exception {
        String source = """
            class A {
                void broken() { helper(1; }
                void later(String text) { text; }
                void helper(int number) {}
            }
            """;
        onFx(() -> {
            editor.replaceText(source);
            editor.requestFocus();
            editor.moveTo(source.indexOf("text;") + "text".length());
            for (char character : ".sub".toCharArray()) {
                editor.insertText(editor.getCaretPosition(), String.valueOf(character));
            }

            return null;
        });

        awaitFx(() -> {
            Node popup = visibleNode(".completion-popup");
            return popup != null && popup.lookup(".list-view") instanceof ListView<?> list
                && list.getItems().stream()
                    .anyMatch(entry -> entry instanceof CompletionItem item && "substring".equals(item.insertText()));
        });

        onFx(() -> {
            ListView<?> list = (ListView<?>) Objects
                .requireNonNull(visibleNode(".completion-popup").lookup(".list-view"));
            Object suggestion = list.getItems().stream()
                .filter(entry -> entry instanceof CompletionItem item && "substring".equals(item.insertText()))
                .findFirst().orElseThrow();
            list.getSelectionModel().select(list.getItems().indexOf(suggestion));
            press(list, KeyCode.ENTER, false);
            assertEquals(source.replace("text;", "text.substring();"), editor.getText());
            return null;
        });
    }

    @Test
    public void quickFixesUseUnsavedTextAndRemoveTheUnusedImport() throws Exception {
        onFx(() -> {
            editor.insertText(editor.getText().indexOf("}\n", editor.getText().indexOf("ambiguous")),
                "    // unsaved\n");
            return null;
        });

        String unsaved = onFx(editor::getText);
        moveTo("java.util.Map");

        CodeActionMenu menu = openMenu();
        assertEquals(List.of("Remove unused import 'java.util.Map'"), titles(menu));
        awaitFx(() -> !menu.previewLines().isEmpty());
        pressInMenu(KeyCode.ENTER);

        String removed = unsaved.replace("import java.util.Map;\n", "");
        awaitFx(() -> editor.getText().equals(removed));
        onFx(() -> {
            editor.undo();
            return null;
        });

        assertEquals(unsaved, onFx(editor::getText));
    }

    @Test
    public void ambiguousImportsAreExplainedAndNeverApplied() throws Exception {
        moveTo("List<String> ambiguous");
        CodeActionMenu menu = openMenu();
        CodeAction action = onFx(menu::actions).getFirst();
        assertFalse(action.available());
        awaitFx(() -> menu.statusText().contains("ambiguous"));

        pressInMenu(KeyCode.ENTER);
        assertFalse(onFx(menu::isClosed));
        assertEquals(SOURCE, onFx(editor::getText));
        assertEquals(-1, onFx(editor::codeActionBulbLine), "Unavailable actions alone show no lightbulb");
    }

    @Test
    public void editingTheDocumentClosesStaleMenusAndUnhelpfulPositionsSayWhy() throws Exception {
        moveTo("ArrayList<");
        CodeActionMenu menu = openMenu();
        onFx(() -> {
            editor.insertText(0, " ");
            return null;
        });

        assertTrue(onFx(menu::isClosed));
        assertNull(onFx(editor::activeCodeActionMenu));

        moveTo("class A");
        onFx(() -> {
            press(editor, KeyCode.ENTER, true);
            return null;
        });

        awaitFx(() -> visibleNode(".navigation-message") != null);
        assertNull(onFx(editor::activeCodeActionMenu));
    }

    @Test
    public void closingTheEditorClosesItsMenuAndStopsOfferingActions() throws Exception {
        moveTo("ArrayList<");
        CodeActionMenu menu = openMenu();
        onFx(() -> {
            editor.close();
            return null;
        });

        assertTrue(onFx(menu::isClosed));
        assertNull(onFx(editor::activeCodeActionMenu));
        assertFalse(onFx(editor::supportsCodeActions));
        assertEquals(-1, onFx(editor::codeActionBulbLine));
    }

    @Test
    public void importsUsedThroughUnicodeEscapesOrDocumentationLinksOfferNoRemoval() throws Exception {
        Path escaped = projectDirectory.resolve("src/main/java/p/Escaped.java");
        Files.writeString(escaped, """
            package p;

            import java.util.List;
            import java.util.Set;

            /// Collects [Set] values.
            class Escaped {
                \\u004Cist<String> names;
            }
            """);
        editor = open(escaped);
        for (String marker : List.of("java.util.List", "java.util.Set")) {
            moveTo(marker);
            onFx(() -> {
                press(editor, KeyCode.ENTER, true);
                return null;
            });

            awaitFx(() -> visibleNode(".navigation-message") != null);
            assertNull(onFx(editor::activeCodeActionMenu), marker);
        }

        assertEquals(Files.readString(escaped), onFx(editor::getText));
    }

    @Test
    public void previewShowsAnImportInsertedAfterThePackageAndMatchesTheAppliedText() throws Exception {
        Path bare = projectDirectory.resolve("src/main/java/p/Bare.java");
        String source = """
            package p;

            class Bare {
                ArrayList<String> names;
            }
            """;
        Files.writeString(bare, source);
        editor = open(bare);
        moveTo("ArrayList<");

        CodeActionMenu menu = openMenu();
        assertEquals(List.of("Import 'java.util.ArrayList'"), titles(menu));
        awaitFx(() -> !menu.previewLines().isEmpty());
        assertEquals("  1:  package p;  →  package p; ⏎ import java.util.ArrayList;",
            onFx(menu::previewLines).get(1));

        pressInMenu(KeyCode.ENTER);
        String imported = source.replace("package p;\n", "package p;\n\nimport java.util.ArrayList;\n");
        awaitFx(() -> editor.getText().equals(imported));
    }

    @Test
    public void actionsPreparedBeforeTheLanguageUnloadsAreNeverApplied() throws Exception {
        moveTo("ArrayList<");
        CodeActionMenu menu = openMenu();
        awaitFx(() -> !menu.previewLines().isEmpty());
        try {
            onFx(() -> {
                LanguageSupportRegistry.unregister("java");
                return null;
            });

            assertFalse(onFx(editor::supportsCodeActions));
            pressInMenu(KeyCode.ENTER);
            assertEquals(SOURCE, onFx(editor::getText));
            assertFalse(Services.IDE_STATE.getWorkspaceEdits().orElseThrow().canUndo());
        } finally {
            onFx(() -> {
                menu.close();
                LanguageSupports.initialize();
                return null;
            });
        }
    }

    private CodeEditorPane open(Path path) throws Exception {
        EditorTab tab = onFx(() -> openInTabPane(path, tabs));
        return (CodeEditorPane) tab.view().activeEditor();
    }

    private CodeActionMenu openMenu() throws Exception {
        onFx(() -> {
            press(editor, KeyCode.ENTER, true);
            return null;
        });

        awaitFx(() -> editor.activeCodeActionMenu() != null);
        return onFx(editor::activeCodeActionMenu);
    }

    private static List<String> titles(CodeActionMenu menu) throws Exception {
        return onFx(() -> menu.actions().stream().map(CodeAction::title).toList());
    }

    private void moveTo(String marker) throws Exception {
        onFx(() -> {
            int offset = editor.getText().indexOf(marker);
            assertTrue(offset >= 0, marker);
            editor.moveTo(offset + 1);
            return null;
        });
    }

    private static void pressInMenu(KeyCode code) throws Exception {
        onFx(() -> {
            press(Objects.requireNonNull(visibleNode(".code-action-list")), code, false);
            return null;
        });
    }

    private static void press(Node node, KeyCode code, boolean alt) {
        node.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false));
    }

    private static void firePrimaryMouseEvent(Node node, EventType<MouseEvent> type) {
        var center = new Point2D(node.getLayoutBounds().getCenterX(), node.getLayoutBounds().getCenterY());
        Point2D scene = node.localToScene(center);
        Point2D screen = node.localToScreen(center);
        node.fireEvent(new MouseEvent(type, scene.getX(), scene.getY(), screen.getX(), screen.getY(),
            MouseButton.PRIMARY, 1, false, false, false, false, type == MouseEvent.MOUSE_PRESSED,
            false, false, false, false, true, new PickResult(node, scene.getX(), scene.getY())));
    }

    private static Node visibleNode(String selector) {
        return Window.getWindows().stream().filter(Window::isShowing)
            .map(window -> window.getScene().lookup(selector))
            .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!onFx(condition)) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for code action UI");
            Thread.sleep(20);
        }
    }

    private EditorTab openInTabPane(Path path, DetachableTabPane tabs) throws ReflectiveOperationException {
        Class<?> requestType = Class.forName(EditorTabManager.class.getName() + "$TabOpenRequest");
        Method normalRequest = requestType.getDeclaredMethod("normal");
        normalRequest.setAccessible(true);
        Method open = EditorTabManager.class.getDeclaredMethod("openInTabPane", Project.class, Path.class,
            DetachableTabPane.class, requestType);
        open.setAccessible(true);
        return (EditorTab) open.invoke(Services.EDITOR_TAB_MANAGER, project, path, tabs,
            normalRequest.invoke(null));
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread())
            return action.call();

        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }
}
