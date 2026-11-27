package dev.railroadide.railroad.ide.ui.codeeditor;

import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.language.impl.groovy.GroovyEditorFeatures;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.request.api.CancellationToken;
import dev.railroadide.railroad.ide.sst.request.api.CancellationTokenSource;
import dev.railroadide.railroad.ide.ui.JavaEditorFeatures;
import dev.railroadide.railroad.ide.ui.JsonCodeEditorPane;
import dev.railroadide.railroad.project.RailroadProject;
import dev.railroadide.railroad.settings.IndentMode;
import dev.railroadide.railroad.settings.Settings;
import dev.railroadide.railroad.settings.keybinds.KeybindData;
import dev.railroadide.railroad.settings.keybinds.Keybinds;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    public void enterCopiesSpacesAndTabsEvenOnAnIndentedBlankLine() throws Exception {
        String text = "first\n\t  second";
        Path file = Files.writeString(directory.resolve("Example.java"), text);
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                editor.moveTo(text.length());
                pressEnter(editor);
                assertEquals(text + "\n\t  ", editor.getText());
                assertEquals(editor.getLength(), editor.getCaretPosition());

                pressEnter(editor);
                assertEquals(text + "\n\t  \n\t  ", editor.getText());
            }

            return null;
        });
    }

    @Test
    public void enterReplacesASelectionUsingIndentationFromItsStartLine() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "  first\n    second");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, null, CancellationToken.none())) {
                editor.selectRange(13, 4);
                pressEnter(editor);
                assertEquals("  fi\n  econd", editor.getText());
                assertEquals(7, editor.getCaretPosition());

                editor.setEditable(false);
                pressEnter(editor);
                assertEquals("  fi\n  econd", editor.getText());
            }

            return null;
        });
    }

    @Test
    public void backspaceReturnsToThePreviousConfiguredIndentationStop() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            int originalWidth = Settings.INDENT_WIDTH.getOrDefaultValue();
            boolean originalSmartBackspace = Settings.SMART_BACKSPACE.getOrDefaultValue();
            Settings.INDENT_WIDTH.setValue(3);
            Settings.SMART_BACKSPACE.setValue(true);
            try (CodeEditorPane editor = open(file, null, CancellationToken.none())) {
                assertBackspace(editor, "head\n         |tail", "head\n      |tail");
                assertBackspace(editor, "head\n     |   tail", "head\n   |   tail");
                assertBackspace(editor, "head\n  |", "head\n|");
                assertBackspace(editor, "head\n   |\ntail", "head\n|\ntail");
            } finally {
                Settings.SMART_BACKSPACE.setValue(originalSmartBackspace);
                Settings.INDENT_WIDTH.setValue(originalWidth);
            }

            return null;
        });
    }

    @Test
    public void backspaceReversesTheSpacesJustInsertedByTab() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            IndentMode originalMode = Settings.INDENT_MODE.getOrDefaultValue();
            int originalWidth = Settings.INDENT_WIDTH.getOrDefaultValue();
            boolean originalSmartBackspace = Settings.SMART_BACKSPACE.getOrDefaultValue();
            Settings.INDENT_MODE.setValue(IndentMode.SPACES);
            Settings.INDENT_WIDTH.setValue(4);
            Settings.SMART_BACKSPACE.setValue(true);
            try (CodeEditorPane editor = open(file, null, CancellationToken.none())) {
                for (String before : List.of("|", "  |code", "code|")) {
                    String source = before.replace("|", "");
                    int caret = before.indexOf('|');
                    editor.replaceText(source);
                    editor.moveTo(caret);
                    pressKey(editor, KeyCode.TAB);
                    assertEquals(before.replace("|", "    "), editor.getText(), before);
                    assertEquals(caret + 4, editor.getCaretPosition(), before);
                    pressKey(editor, KeyCode.TAB);
                    assertEquals(before.replace("|", "        "), editor.getText(), before);
                    pressKey(editor, KeyCode.BACK_SPACE);
                    assertEquals(before.replace("|", "    "), editor.getText(), before);
                    assertEquals(caret + 4, editor.getCaretPosition(), before);
                    pressKey(editor, KeyCode.BACK_SPACE);
                    assertEquals(source, editor.getText(), before);
                    assertEquals(caret, editor.getCaretPosition(), before);
                }

                editor.replaceText("code");
                editor.moveTo(4);
                pressKey(editor, KeyCode.TAB);
                editor.moveTo(0);
                editor.moveTo(8);
                pressKey(editor, KeyCode.BACK_SPACE);
                assertEquals("code   ", editor.getText());
            } finally {
                Settings.SMART_BACKSPACE.setValue(originalSmartBackspace);
                Settings.INDENT_MODE.setValue(originalMode);
                Settings.INDENT_WIDTH.setValue(originalWidth);
            }

            return null;
        });
    }

    @Test
    public void smartBackspaceCanBeToggledInAnOpenEditor() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            IndentMode originalMode = Settings.INDENT_MODE.getOrDefaultValue();
            int originalWidth = Settings.INDENT_WIDTH.getOrDefaultValue();
            boolean originalSmartBackspace = Settings.SMART_BACKSPACE.getOrDefaultValue();
            Settings.INDENT_MODE.setValue(IndentMode.SPACES);
            Settings.INDENT_WIDTH.setValue(4);
            Settings.SMART_BACKSPACE.setValue(false);
            try (CodeEditorPane editor = open(file, null, CancellationToken.none())) {
                pressKey(editor, KeyCode.TAB);
                assertEquals("    ", editor.getText());
                pressKey(editor, KeyCode.BACK_SPACE);
                assertEquals("   ", editor.getText());

                Settings.SMART_BACKSPACE.setValue(true);
                pressKey(editor, KeyCode.BACK_SPACE);
                assertEquals("", editor.getText());
                assertEquals(0, editor.getCaretPosition());
            } finally {
                Settings.SMART_BACKSPACE.setValue(originalSmartBackspace);
                Settings.INDENT_MODE.setValue(originalMode);
                Settings.INDENT_WIDTH.setValue(originalWidth);
            }

            return null;
        });
    }

    @Test
    public void backspaceHandlesTabsAndSpacesTogetherInLeadingIndentation() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            int originalIndentWidth = Settings.INDENT_WIDTH.getOrDefaultValue();
            int originalTabWidth = Settings.TAB_WIDTH.getOrDefaultValue();
            boolean originalSmartBackspace = Settings.SMART_BACKSPACE.getOrDefaultValue();
            Settings.INDENT_WIDTH.setValue(4);
            Settings.TAB_WIDTH.setValue(6);
            Settings.SMART_BACKSPACE.setValue(true);
            try (CodeEditorPane editor = open(file, null, CancellationToken.none())) {
                assertBackspace(editor, "\t    |code", "\t  |code");
                assertBackspace(editor, "\t  |code", "\t|code");
                assertBackspace(editor, "\t\t|code", "\t|code");
                assertBackspace(editor, "\t|code", "|code");
            } finally {
                Settings.SMART_BACKSPACE.setValue(originalSmartBackspace);
                Settings.INDENT_WIDTH.setValue(originalIndentWidth);
                Settings.TAB_WIDTH.setValue(originalTabWidth);
            }

            return null;
        });
    }

    @Test
    public void backspaceUsesOrdinaryDeletionForSelectionsAndOutsideIndentation() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            boolean originalSmartBackspace = Settings.SMART_BACKSPACE.getOrDefaultValue();
            Settings.SMART_BACKSPACE.setValue(true);
            try (CodeEditorPane editor = open(file, null, CancellationToken.none())) {
                assertBackspace(editor, "  value    |other", "  value   |other");
                assertBackspace(editor, "  valu|e", "  val|e");
                assertBackspace(editor, "first\n|second", "first|second");
                assertBackspace(editor, "|\nsecond", "|\nsecond");

                editor.replaceText("        code");
                editor.selectRange(8, 7);
                pressKey(editor, KeyCode.BACK_SPACE);
                assertEquals("       code", editor.getText());
                assertEquals(7, editor.getCaretPosition());

                editor.setEditable(false);
                pressKey(editor, KeyCode.BACK_SPACE);
                assertEquals("       code", editor.getText());
            } finally {
                Settings.SMART_BACKSPACE.setValue(originalSmartBackspace);
            }

            return null;
        });
    }

    private static void assertBackspace(CodeEditorPane editor, String before, String after) {
        editor.replaceText(before.replace("|", ""));
        editor.moveTo(before.indexOf('|'));
        pressKey(editor, KeyCode.BACK_SPACE);
        assertEquals(after.replace("|", ""), editor.getText(), before);
        assertEquals(after.indexOf('|'), editor.getCaretPosition(), before);
    }

    @Test
    public void ctrlBackspaceSkipsWhitespaceAndStopsAtTheStartOfTheLine() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            Keybinds.initialize();
            try (CodeEditorPane editor = open(file, null, CancellationToken.none())) {
                assertCtrlBackspace(editor, "first\n  alpha beta \t  |\nnext", "first\n  alpha |\nnext");
                assertCtrlBackspace(editor, "first\n  \t  |", "first\n|");
                assertCtrlBackspace(editor, "first\n|next", "first\n|next");
                assertCtrlBackspace(editor, "first\n  word|", "first\n  |");
                assertCtrlBackspace(editor, "one two \t |next", "one |next");

                editor.replaceText("one\ntwo");
                editor.selectRange(2, 5);
                pressKey(editor, KeyCode.BACK_SPACE, true);
                assertEquals("onwo", editor.getText());
                editor.undo();
                assertEquals("one\ntwo", editor.getText());

                editor.setEditable(false);
                editor.moveTo(editor.getLength());
                pressKey(editor, KeyCode.BACK_SPACE, true);
                assertEquals("one\ntwo", editor.getText());
            }

            return null;
        });
    }

    @Test
    public void deletePreviousWordOnLineCanBeRebound() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "first\n  word \t");
        onFx(() -> {
            var keybind = Keybinds.DELETE_PREVIOUS_WORD_ON_LINE;
            List<KeybindData> originalKeys = List.copyOf(keybind.getKeys());
            keybind.getKeys().setAll(new KeybindData(KeyCode.F9, new KeyCombination.Modifier[0]));
            try (CodeEditorPane editor = open(file, null, CancellationToken.none())) {
                editor.moveTo(editor.getLength());
                pressKey(editor, KeyCode.F9);
                assertEquals("first\n  ", editor.getText());
            } finally {
                keybind.getKeys().setAll(originalKeys);
            }

            return null;
        });
    }

    private static void assertCtrlBackspace(CodeEditorPane editor, String before, String after) {
        editor.replaceText(before.replace("|", ""));
        editor.moveTo(before.indexOf('|'));
        pressKey(editor, KeyCode.BACK_SPACE, true);
        assertEquals(after.replace("|", ""), editor.getText(), before);
        assertEquals(after.indexOf('|'), editor.getCaretPosition(), before);
    }

    @Test
    public void enterBetweenJsonBracketsKeepsTheInnerIndentAndCaret() throws Exception {
        String text = "  []";
        Path file = Files.writeString(directory.resolve("example.json"), text);
        onFx(() -> {
            var project = new RailroadProject(directory, "Editor features", new WritableImage(1, 1));
            var config = new CodeEditorConfig("json", null, null, null, null, null);
            try (var editor = new JsonCodeEditorPane(project, DocumentId.create(), file, config)) {
                editor.moveTo(3);
                pressEnter(editor);
                assertEquals("  [\n      \n  ]", editor.getText());
                assertEquals(10, editor.getCaretPosition());
            }

            return null;
        });
    }

    @Test
    public void typingJavaBracketsInsertsPairsAndMovesOverTheCloser() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                for (String pair : List.of("()", "[]", "{}")) {
                    editor.replaceText("example;");
                    editor.moveTo("example".length());
                    typeCharacter(editor, pair.substring(0, 1));
                    assertEquals("example" + pair + ";", editor.getText());
                    assertEquals("example".length() + 1, editor.getCaretPosition());
                    typeCharacter(editor, pair.substring(1));
                    assertEquals("example" + pair + ";", editor.getText());
                    assertEquals("example".length() + 2, editor.getCaretPosition());
                    editor.undo();
                    assertEquals("example;", editor.getText());
                }
            }

            return null;
        });
    }

    @Test
    public void typingOpeningBracketsWrapsJavaSelectionsAndPreservesTheirDirection() throws Exception {
        String prefix = "before";
        String selected = "first\nsecond";
        String suffix = "after";
        String source = prefix + selected + suffix;
        int start = prefix.length();
        int end = start + selected.length();
        Path file = Files.writeString(directory.resolve("Example.java"), source);
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                for (String pair : List.of("()", "[]", "{}")) {
                    for (boolean reversed : List.of(false, true)) {
                        int anchor = reversed ? end : start;
                        int caret = reversed ? start : end;
                        editor.selectRange(anchor, caret);
                        typeCharacter(editor, pair.substring(0, 1));
                        String wrapped = prefix + pair.charAt(0) + selected + pair.charAt(1) + suffix;
                        assertEquals(wrapped, editor.getText());
                        assertEquals(selected, editor.getSelectedText());
                        assertEquals(anchor + 1, editor.getAnchor());
                        assertEquals(caret + 1, editor.getCaretPosition());

                        editor.undo();
                        assertEquals(source, editor.getText());
                        editor.redo();
                        assertEquals(wrapped, editor.getText());
                        editor.undo();
                    }
                }
            }

            return null;
        });
    }

    @Test
    public void typingOpeningBracketWrapsJsonSelectionBeforeItsPairInsertionRuns() throws Exception {
        String source = "{\"name\": \"Example\", \"version\": \"1\", \"extra\": \"selected\"}";
        Path file = Files.writeString(directory.resolve("example.json"), source);
        onFx(() -> {
            var project = new RailroadProject(directory, "Editor features", new WritableImage(1, 1));
            var config = new CodeEditorConfig("json", null, null, null, null, null);
            try (var editor = new JsonCodeEditorPane(project, DocumentId.create(), file, config)) {
                String selected = "\"selected\"";
                int start = source.indexOf(selected);
                editor.selectRange(start, start + selected.length());
                typeCharacter(editor, "[");
                assertEquals(source.replace(selected, "[" + selected + "]"), editor.getText());
                assertEquals(selected, editor.getSelectedText());
            }

            return null;
        });
    }

    @Test
    public void javaBracketPairingHonorsStringsSettingAndLeavesCommentsAlone() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            boolean original = Settings.AUTO_PAIR_INSIDE_STRINGS.getOrDefaultValue();
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                for (boolean enabled : List.of(false, true)) {
                    Settings.AUTO_PAIR_INSIDE_STRINGS.setValue(enabled);
                    for (String prefix : List.of("// comment ", "/* comment ", "String value = \"", "\"\"\"\n")) {
                        editor.replaceText(prefix);
                        editor.moveTo(prefix.length());
                        typeCharacter(editor, "[");
                        assertEquals(prefix + (enabled && !prefix.startsWith("/") ? "[]" : "["), editor.getText());
                    }
                }
            } finally {
                Settings.AUTO_PAIR_INSIDE_STRINGS.setValue(original);
            }

            return null;
        });
    }

    private static void typeCharacter(CodeEditorPane editor, String character) {
        editor.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED,
            false, false, false, false));
    }

    @Test
    public void enterAfterAJavaOpeningBraceUsesTheConfiguredIndentation() throws Exception {
        String text = "class Example {";
        Path file = Files.writeString(directory.resolve("Example.java"), text);
        onFx(() -> {
            IndentMode originalMode = Settings.INDENT_MODE.getOrDefaultValue();
            int originalWidth = Settings.INDENT_WIDTH.getOrDefaultValue();
            Settings.INDENT_MODE.setValue(IndentMode.SPACES);
            Settings.INDENT_WIDTH.setValue(2);
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                editor.moveTo(text.length());
                pressEnter(editor);
                assertEquals(text + "\n  ", editor.getText());
            } finally {
                Settings.INDENT_MODE.setValue(originalMode);
                Settings.INDENT_WIDTH.setValue(originalWidth);
            }

            return null;
        });
    }

    @Test
    public void enterBetweenJavaBracesAlignsTheClosingBraceAndIsOneUndoStep() throws Exception {
        String opening = "class Example {\n\tvoid method() {";
        String text = opening + "  }\n}";
        Path file = Files.writeString(directory.resolve("Example.java"), text);
        onFx(() -> {
            IndentMode originalMode = Settings.INDENT_MODE.getOrDefaultValue();
            Settings.INDENT_MODE.setValue(IndentMode.TABS);
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                editor.moveTo(opening.length());
                pressEnter(editor);
                assertEquals(opening + "\n\t\t\n\t}\n}", editor.getText());
                assertEquals(opening.length() + 3, editor.getCaretPosition());

                editor.undo();
                assertEquals(text, editor.getText());
            } finally {
                Settings.INDENT_MODE.setValue(originalMode);
            }

            return null;
        });
    }

    @Test
    public void enterAfterAMultilineJavaCallReturnsToTheCallsIndentation() throws Exception {
        String before = """
            class Example {
                void onRender() {
                    ModelSubmitUtils.submitModel(queue, model, null,
                        poses, model.renderType(TEXTURE),
                        light, overlay, 0, state.breakProgress());
            """.stripTrailing();
        String after = "\n    }\n}";
        Path file = Files.writeString(directory.resolve("Example.java"), before + after);
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                editor.moveTo(before.length());
                pressEnter(editor);
                assertEquals(before + "\n        " + after, editor.getText());
                assertEquals(before.length() + 9, editor.getCaretPosition());
            }

            return null;
        });
    }

    @Test
    public void enterKeepsJavaContinuationIndentationInsideAnUnfinishedStatement() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                assertJavaNewline(editor, "submit(\n    model.renderType(TEXTURE),|\n    other);", "    ");
                assertJavaNewline(editor, "for (int i = first(\n    value);|\n    i < limit; i++) {}",
                    "    ");
                assertJavaNewline(editor, "for (int i = 0;\n    i < check();|\n    i++) {}", "    ");
                assertJavaNewline(editor, "// call(\n    // argument);|", "    ");
                assertJavaNewline(editor, "if (ready)\n    return; // comment;| still editing", "    ");
            }

            return null;
        });
    }

    @Test
    public void enterAfterAnUnbracedJavaHeaderIndentsItsBody() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                for (String body : List.of(
                    "if (ready)|",
                    "if (check(value))|use(value);",
                    "if (ready) // use the fast path|",
                    "if (first &&\n    second)|",
                    "if (ready)|\n    use();\nelse {}",
                    "for (;;)|",
                    "for (int i = 0;\n    i < limit; i++)|",
                    "for (var item : items)|",
                    "while (running)|")) {
                    assertJavaNewline(editor, body, editor.indentationUnit());
                }

                assertJavaNewline(editor, "if (outer)\n    if (inner)|", "    " + editor.indentationUnit());
            }

            return null;
        });
    }

    @Test
    public void enterAfterJavaClosingParenthesesOnlyAddsIndentationForUnbracedHeaders() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                assertJavaNewline(editor, "if (ready)|{ use(); }", "");
                assertJavaNewline(editor, "for (var item : items)|\n{ use(item); }", "");
                assertJavaNewline(editor, "submit(input)|", "");
                assertJavaNewline(editor, "if (check(input)|)", "");
                assertJavaNewline(editor, "do use(); while (running)|;", "");
                assertJavaNewline(editor, "// if (ready)|", "");
                assertJavaNewline(editor, "String text = \"if (ready)|\";", "");
            }

            return null;
        });
    }

    @Test
    public void enterAfterUnbracedJavaBodiesAlignsWithTheOuterCompletedStatement() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                for (String body : List.of(
                    "if (!state.multiblockFormed)\n    return;|",
                    "if (!ready)\n    return; // explain;|",
                    "if (first)\n    useFirst();\nelse\n    useSecond();|",
                    "while (ready)\n    if (done)\n        break;|",
                    "for (var item : items)\n    use(item);|",
                    "for (int i = 0; i < limit; i++)\n    if (skip(i))\n        continue;|",
                    "outer:\n    while (running)\n        break outer;|",
                    "if (ready)\n    do\n        step();\n    while (running);|",
                    "if (ready)\n    return submit(\n        input, fallback());|")) {
                    assertJavaNewline(editor, body, "");
                }
            }

            return null;
        });
    }

    @Test
    public void enterBeforeAFollowingJavaClauseStaysAtItsHeaderIndentation() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                assertJavaNewline(editor, "while (ready)\n    if (first)\n        useFirst();|\n"
                    + "    else\n        useSecond();", "    ");
                assertJavaNewline(editor, "if (ready)\n    do\n        step();|\n    while (running);", "    ");
            }

            return null;
        });
    }

    @Test
    public void enterAfterJavaStatementsRespectsBlockBoundariesAndMultilineExpressions() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                assertJavaNewline(editor, "if (ready) {\n    return;|\n}", "    ");
                assertJavaNewline(editor, "run(() -> {\n    if (ready)\n        return;|\n});", "    ");
                assertJavaNewline(editor, "int total = first\n    + second;|", "");
                assertJavaNewline(editor, "var result = service\n    .submit(\n        input);|", "");
            }

            return null;
        });
    }

    private static void assertJavaNewline(CodeEditorPane editor, String body, String indentation) {
        String source = "class Example {\n    void example() {\n" + body.indent(8) + "    }\n}";
        int caret = source.indexOf('|');
        String insertion = "\n        " + indentation;
        editor.replaceText(source.replace("|", ""));
        editor.moveTo(caret);
        pressEnter(editor);
        assertEquals(source.replace("|", insertion), editor.getText(), body);
        assertEquals(caret + insertion.length(), editor.getCaretPosition(), body);
    }

    @Test
    public void enterAfterBracesInJavaCommentsAndLiteralsOnlyCopiesIndentation() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new JavaEditorFeatures(), CancellationToken.none())) {
                for (String text : List.of("  // {", "/*\n  {", "  String value = \"{", "String value = \"\"\"\n  {")) {
                    editor.replaceText(text);
                    editor.moveTo(text.length());
                    pressEnter(editor);
                    assertEquals(text + "\n  ", editor.getText());
                }
            }

            return null;
        });
    }

    @Test
    public void enterIndentsGroovyClosuresAndCollections() throws Exception {
        String closure = "tasks.register('🎨') {";
        Path file = Files.writeString(directory.resolve("build.gradle"), closure + "}");
        onFx(() -> {
            IndentMode originalMode = Settings.INDENT_MODE.getOrDefaultValue();
            Settings.INDENT_MODE.setValue(IndentMode.TABS);
            try (CodeEditorPane editor = open(file, new GroovyEditorFeatures(), CancellationToken.none())) {
                editor.moveTo(closure.length());
                pressEnter(editor);
                assertEquals(closure + "\n\t\n}", editor.getText());
                assertEquals(closure.length() + 2, editor.getCaretPosition());

                editor.replaceText("\tdef properties = [name: 'example']");
                editor.moveTo("\tdef properties = [".length());
                pressEnter(editor);
                assertEquals("\tdef properties = [\n\t\tname: 'example']", editor.getText());
            } finally {
                Settings.INDENT_MODE.setValue(originalMode);
            }

            return null;
        });
    }

    @Test
    public void enterAfterBracketsInGroovyCommentsAndLiteralsOnlyCopiesIndentation() throws Exception {
        Path file = Files.writeString(directory.resolve("build.gradle"), "");
        onFx(() -> {
            try (CodeEditorPane editor = open(file, new GroovyEditorFeatures(), CancellationToken.none())) {
                for (String source : List.of("  // {|", "/*\n  [|", "  def text = \"{|\"",
                    "  def pattern = /[|]/", "  def pattern = $/{|/$")) {
                    int caret = source.indexOf('|');
                    editor.replaceText(source.replace("|", ""));
                    editor.moveTo(caret);
                    pressEnter(editor);
                    assertEquals(source.replace("|", "\n  "), editor.getText());
                }
            }

            return null;
        });
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

    private static void pressEnter(CodeEditorPane editor) {
        pressKey(editor, KeyCode.ENTER);
    }

    private static void pressKey(CodeEditorPane editor, KeyCode key) {
        pressKey(editor, key, false);
    }

    private static void pressKey(CodeEditorPane editor, KeyCode key, boolean control) {
        editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, control, false, false));
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
