package dev.railroadide.railroad.ide.ui.refactor;

import com.panemu.tiwulfx.control.dock.DetachableTabPane;
import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.command.CommandContext;
import dev.railroadide.railroad.command.EditCommands;
import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.projectexplorer.ProjectExplorerPane;
import dev.railroadide.railroad.ide.projectexplorer.PathTreeCell;
import dev.railroadide.railroad.ide.sst.impl.java.CompiledJava;
import dev.railroadide.railroad.localization.L18n;
import dev.railroadide.railroad.localization.Languages;
import dev.railroadide.railroad.ide.language.LanguageSupports;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorPane;
import dev.railroadide.railroad.ide.ui.editor.EditorTab;
import dev.railroadide.railroad.ide.ui.editor.EditorTabManager;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.plugin.spi.events.ProjectEvent;
import dev.railroadide.railroad.project.RailroadProject;
import dev.railroadide.railroad.settings.keybinds.Keybinds;
import dev.railroadide.railroad.settings.keybinds.KeybindContexts;
import dev.railroadide.railroad.settings.keybinds.KeybindHandler;
import dev.railroadide.railroad.utility.OperatingSystem;
import dev.railroadide.railroad.theme.ThemeManager;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class InlineRenameEditorTest {
    private static final String SOURCE = """
        package p;

        class A {
            static int value() { return 1; }
            static int other() { return 2; }
            String example() { return "value" + value() + value(); } // value
        }
        """;

    @TempDir
    public Path projectDirectory;
    private Project project;
    private Stage stage;
    private CodeEditorPane editor;
    private Path file;
    private DetachableTabPane tabs;
    private Path use;
    private ProjectExplorerPane explorer;

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
        projectDirectory = projectDirectory.toRealPath();
        file = projectDirectory.resolve("src/main/java/p/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        use = file.resolveSibling("Use.java");
        Files.writeString(use, "package p; class Use { int read() { return A.value(); } }");
        onFx(() -> {
            L18n.loadLanguage(Languages.EN_US);
            LanguageSupports.initialize();
            if (ThemeManager.getTheme() == null) {
                ThemeManager.setTheme("");
            }

            project = new RailroadProject(projectDirectory, "Inline rename", new WritableImage(1, 1));
            Services.IDE_STATE.setCurrentProject(project);
            return null;
        });

        Services.IDE_STATE.getWorkspaceConfiguration().ready().get(10, TimeUnit.SECONDS);
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

        open(use);
        editor = open(file);
    }

    @AfterEach
    public void closeProject() throws Exception {
        onFx(() -> {
            if (explorer != null) {
                explorer.close();
            }

            stage.close();
            Services.EDITOR_TAB_MANAGER.discardUnsavedChangesOnClose();
            Services.EDITOR_TAB_MANAGER.prepareProjectClose();
            Railroad.EVENT_BUS.publish(new ProjectEvent(project, ProjectEvent.EventType.CLOSED));
            return null;
        });

        assertTrue(Services.IDE_STATE.getWorkspaceEdits().isEmpty());
    }

    @Test
    public void mirrorsResolvedNamesAndDiscardsWithEscape() throws Exception {
        InlineRenameEditor draft = startRename();
        onFx(() -> {
            type(draft, "x");
            assertEquals(SOURCE.replace("value()", "x()"), draft.getText());
            CommandContext<Void> context = CommandContext.forProject(project, draft);
            assertSame(draft, draft.getScene().getFocusOwner());
            assertTrue(EditCommands.UNDO.canExecute(context));
            EditCommands.UNDO.execute(context);
            assertEquals(SOURCE, draft.getText());
            EditCommands.REDO.execute(context);
            assertEquals(SOURCE.replace("value()", "x()"), draft.getText());
            type(draft, "yz");
            assertEquals(SOURCE.replace("value()", "xyz()"), draft.getText());
            draft.selectAll();
            draft.replaceSelection("");
            type(draft, "price");
            assertEquals(SOURCE.replace("value()", "price()"), draft.getText());
            assertEquals(SOURCE, editor.getText());
            press(draft, KeyCode.ESCAPE, false);
            assertNull(visibleNode(".inline-rename-editor"));
            assertFalse(editor.getUndoManager().isUndoAvailable());
            return null;
        });

        assertEquals(SOURCE, Files.readString(file));
        assertFalse(Services.IDE_STATE.getWorkspaceEdits().orElseThrow().canUndo());
    }

    @Test
    public void continuesFromAConflictThenConfirmsAndUndoesAcrossFiles() throws Exception {
        InlineRenameEditor draft = reviewConflict();
        click("railroad.rename.continue");
        onFx(() -> {
            assertSame(draft, visibleNode(".inline-rename-editor"));
            assertEquals(SOURCE.replace("value()", "other()"), draft.getText());
            draft.selectAll();
            type(draft, "compute");
            press(draft, KeyCode.ENTER, false);
            return null;
        });

        click("railroad.rename.apply");
        assertEquals(SOURCE.replace("value()", "compute()"), onFx(editor::getText));
        CodeEditorPane other = open(use);
        String caller = onFx(other::getText);
        assertTrue(caller.contains("A.compute()"), caller);
        onFx(() -> {
            other.undo();
            assertTrue(other.getText().contains("A.value()"));
            assertEquals(SOURCE, editor.getText());
            return null;
        });
    }

    @Test
    public void explorerRenamesTheFileAndUsagesAndUndoesOnceWithAnUnsavedCaller(@TempDir Path compiler)
        throws Exception {
        Path callerFile = file.getParent().resolveSibling("q").resolve("Use.java");
        Files.createDirectories(callerFile.getParent());
        Files.writeString(callerFile, "package q; class Use {}");
        CodeEditorPane caller = open(callerFile);
        String declaration = "package p; public class A { public A() {} public static int value() { return 1; } }";
        String draft = "package q; import p.A; class Use { A make() { return new A(); } } // unsaved";
        Path renamed = file.resolveSibling("Copper.java");
        onFx(() -> {
            var layout = new BorderPane();
            stage.getScene().setRoot(layout);
            layout.setCenter(tabs);
            KeybindHandler.registerCapture(KeybindContexts.of("railroad:ide"), layout);
            explorer = new ProjectExplorerPane(project);
            layout.setLeft(explorer);
            editor.replaceText(declaration);
            caller.replaceText(draft);
            assertTrue(caller.dirtyProperty().get());
            explorer.revealPath(file);
            layout.applyCss();
            layout.layout();
            assertEquals(file, explorer.commandTarget().path());
            return null;
        });

        TextField field = onFx(() -> {
            PathTreeCell cell = explorer.lookupAll(".tree-cell").stream().filter(PathTreeCell.class::isInstance)
                .map(PathTreeCell.class::cast)
                .filter(row -> row.getItem() != null && file.equals(row.getItem().getPath()))
                .findFirst().orElseThrow();
            explorer.renameSelectedItem();
            assertTrue(cell.isEditing());
            return assertInstanceOf(TextField.class, cell.getGraphic().lookup(".text-field"));
        });

        awaitFx(() -> field.getScene() != null && field.getSkin() != null);
        onFx(() -> {
            assertEquals("A", field.getSelectedText());
            field.replaceSelection("Copper");
            press(field, KeyCode.ENTER, false);
            assertNull(explorer.commandTarget().tree().getEditingItem(), "Enter should submit the filename");
            return null;
        });

        click("railroad.rename.apply");

        String expectedCaller = draft.replace("p.A", "p.Copper").replace(" A ", " Copper ").replace("new A()",
            "new Copper()");
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(renamed));
        assertEquals(declaration.replace(" A", " Copper"), onFx(editor::getText));
        assertEquals(expectedCaller, onFx(caller::getText));
        assertEquals(List.of(), CompiledJava.errors(compiler,
            Map.of("p/Copper.java", onFx(editor::getText), "q/Use.java", onFx(caller::getText))));

        onFx(() -> {
            Node tree = explorer.lookup(".tree-view");
            tree.requestFocus();
            assertTrue(EditCommands.UNDO.canExecute(CommandContext.forProject(project, explorer)));
            tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.Z,
                false, !OperatingSystem.isMac(), false, OperatingSystem.isMac()));
            assertFalse(Services.IDE_STATE.getWorkspaceEdits().orElseThrow().canUndo());
            assertEquals(declaration, editor.getText());
            assertEquals(draft, caller.getText());
            assertTrue(Files.exists(file));
            assertFalse(Files.exists(renamed));
            return null;
        });
    }

    @Test
    public void discardsOrExplicitlyOverridesADeclarationCollision() throws Exception {
        reviewConflict();
        click("railroad.rename.discard");
        assertEquals(SOURCE, onFx(editor::getText));
        assertFalse(Services.IDE_STATE.getWorkspaceEdits().orElseThrow().canUndo());

        reviewConflict();
        click("railroad.rename.apply_anyway");
        assertEquals(SOURCE.replace("value()", "other()"), onFx(editor::getText));
        assertTrue(Services.IDE_STATE.getWorkspaceEdits().orElseThrow().canUndo());
    }

    private InlineRenameEditor startRename() throws Exception {
        onFx(() -> {
            editor.moveTo(SOURCE.lastIndexOf("value()") + 1);
            press(editor, KeyCode.F6, true);
            return null;
        });

        awaitFx(() -> visibleNode(".inline-rename-editor") != null);
        assertNull(onFx(() -> visibleNode(".rename-dialog")));
        return (InlineRenameEditor) onFx(() -> visibleNode(".inline-rename-editor"));
    }

    private InlineRenameEditor reviewConflict() throws Exception {
        InlineRenameEditor draft = startRename();
        onFx(() -> {
            type(draft, "other");
            press(draft, KeyCode.ENTER, false);
            return null;
        });

        awaitFx(() -> button("railroad.rename.apply_anyway") != null
            && !button("railroad.rename.apply_anyway").isDisabled());
        assertEquals(SOURCE, onFx(editor::getText));
        return draft;
    }

    private static void type(Node node, String text) {
        text.chars().forEach(character -> node.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
            Character.toString(character), "", KeyCode.UNDEFINED, false, false, false, false)));
    }

    private static Button button(String key) {
        Node dialog = visibleNode(".rename-dialog");
        if (dialog == null)
            return null;

        return dialog.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.isVisible() && button.getText().equals(L18n.localize(key))).findFirst()
            .orElse(null);
    }

    private static void click(String key) throws Exception {
        awaitFx(() -> button(key) != null && !button(key).isDisabled());
        onFx(() -> {
            button(key).fire();
            return null;
        });
    }

    private CodeEditorPane open(Path path) throws Exception {
        EditorTab tab = onFx(() -> openInTabPane(path, tabs));
        return (CodeEditorPane) tab.view().activeEditor();
    }

    private static void press(Node node, KeyCode code, boolean shift) {
        node.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    private static Node visibleNode(String selector) {
        return Window.getWindows().stream().filter(Window::isShowing)
            .map(window -> window.getScene().lookup(selector))
            .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!onFx(condition)) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for rename UI");
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
