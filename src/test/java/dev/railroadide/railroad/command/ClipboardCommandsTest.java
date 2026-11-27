package dev.railroadide.railroad.command;

import com.kodedu.terminalfx.Terminal;
import com.kodedu.terminalfx.config.TerminalConfig;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorRequestTestSupport;
import dev.railroadide.railroad.settings.keybinds.KeybindContexts;
import dev.railroadide.railroad.settings.keybinds.KeybindHandler;
import dev.railroadide.railroad.settings.keybinds.Keybinds;
import dev.railroadide.railroad.utility.OperatingSystem;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import netscape.javascript.JSObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class ClipboardCommandsTest {
    @BeforeAll
    public static void startFx() throws Exception {
        CodeEditorRequestTestSupport.startFx();
        Keybinds.initialize();
    }

    @Test
    public void pastesEditorSelectionThroughHterm() throws Exception {
        var terminal = CodeEditorRequestTestSupport.onFx(TestTerminal::new);
        terminal.ready.get(10, TimeUnit.SECONDS);
        CodeEditorRequestTestSupport.onFx(() -> {
            withClipboard(() -> {
                String source = "λ 'quoted' \\\nsecond line";
                var editor = new CodeArea(source);
                Scene scene = createShortcutScene(editor, terminal);
                editor.requestFocus();
                assertSame(editor, scene.getFocusOwner());
                editor.selectAll();
                pressShortcut(editor, KeyCode.C);
                assertEquals(source, Clipboard.getSystemClipboard().getString().replace("\r\n", "\n"));

                WebView view = (WebView) terminal.getChildren().getFirst();
                view.requestFocus();
                assertSame(view, scene.getFocusOwner());
                JSObject window = terminal.getWindow();
                window.eval("""
                    window.pastes = [];
                    t.setBracketedPaste(true);
                    t.io.sendString = function(text) { window.pastes.push(text); };
                    """);
                pressShortcut(view, KeyCode.V);
                assertEquals(1, window.eval("pastes.length"));
                assertEquals("\u001b[200~" + source.replace('\n', '\r') + "\u001b[201~", window.eval("pastes[0]"));
                assertEquals(source, editor.getText());

                var reachedTerminal = new AtomicBoolean();
                view.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    reachedTerminal.set(event.getCode() == KeyCode.C);
                    event.consume();
                });

                pressShortcut(view, KeyCode.C);
                assertTrue(reachedTerminal.get());
            });

            return null;
        });
    }

    @Test
    public void copiesSelectionFromReadOnlyConsole() throws Exception {
        CodeEditorRequestTestSupport.onFx(() -> {
            withClipboard(() -> {
                var editor = new CodeArea("editor content");
                var console = new StyleClassedTextArea();
                console.appendText("first line\nselected line\nlast line");
                console.setEditable(false);
                Scene scene = createShortcutScene(editor, console);
                editor.selectAll();
                console.requestFocus();
                assertSame(console, scene.getFocusOwner());
                console.selectRange(24, 11);
                CommandContext<Void> context = CommandContext.forProject(null, scene.getRoot());
                assertTrue(EditCommands.COPY.canExecute(context));
                assertFalse(EditCommands.PASTE.canExecute(context));
                pressShortcut(console, KeyCode.C);
                assertEquals("selected line", Clipboard.getSystemClipboard().getString());
            });

            return null;
        });
    }

    private static Scene createShortcutScene(Node... children) {
        var root = new VBox(children);
        KeybindHandler.registerCapture(KeybindContexts.of("railroad:ide"), root);
        return new Scene(root);
    }

    private static void pressShortcut(Node target, KeyCode code) {
        boolean mac = OperatingSystem.isMac();
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, false, mac));
    }

    private static void withClipboard(Runnable action) throws ReflectiveOperationException {
        // Use JavaFX's in-memory clipboard to keep tests isolated from the desktop clipboard.
        Field systemClipboard = Clipboard.class.getDeclaredField("systemClipboard");
        Method getLocalClipboard = Clipboard.class.getDeclaredMethod("getLocalClipboardImpl");
        systemClipboard.setAccessible(true);
        getLocalClipboard.setAccessible(true);
        Object original = systemClipboard.get(null);
        Clipboard clipboard = (Clipboard) getLocalClipboard.invoke(null);
        clipboard.clear();
        systemClipboard.set(null, clipboard);

        try {
            action.run();
        } finally {
            systemClipboard.set(null, original);
            clipboard.clear();
        }
    }

    public static final class TestTerminal extends Terminal {
        private final CompletableFuture<Void> ready = new CompletableFuture<>();

        private TestTerminal() {
            super(new TerminalConfig(), null);
        }

        @Override
        public void onTerminalReady() {
            setOutputWriter(new StringWriter());
            ready.complete(null);
        }
    }
}
