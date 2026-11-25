package dev.railroadide.railroad.ide.ui;

import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.command.CommandContext;
import dev.railroadide.railroad.command.RunCommands;
import dev.railroadide.railroad.ide.runconfig.EditorJavaLaunch;
import dev.railroadide.railroad.ide.runconfig.RunConfiguration;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.ui.codeeditor.BreakpointGutterBinding;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorConfig;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorPane;
import dev.railroadide.railroad.ide.ui.codeeditor.GutterColumn;
import dev.railroadide.railroad.localization.L18n;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.ui.localized.LocalizedTooltip;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.reactfx.Subscription;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.Deque;

/**
 * Adds Java call-context detection for signature help to the configured code editor.
 */
public class JavaCodeEditorPane extends CodeEditorPane {
    private Subscription launchChanges = Subscription.EMPTY;
    private List<EditorJavaLaunch> launches = List.of();
    private String parsedSource;
    private long launchRequest;
    private final BreakpointGutterBinding breakpointBinding;
    private static final int SIGNATURE_SCAN_WINDOW = 2048;
    private static final Set<String> NON_CALLABLE_PREFIX_KEYWORDS = Set.of(
        "if",
        "for",
        "while",
        "switch",
        "catch",
        "synchronized",
        "try",
        "do");

    /**
     * Creates a Java editor with the supplied language-service configuration.
     *
     * @param project project whose files and workspace are being displayed
     * @param documentId authoritative logical identity shared with the editor tab
     * @param item file to open in the editor
     * @param config language-service configuration for the editor
     */
    public JavaCodeEditorPane(Project project, DocumentId documentId, Path item, CodeEditorConfig config) {
        super(project, documentId, item, config);
        breakpointBinding = new BreakpointGutterBinding(getGutter(), this, this::getFilePath,
            Services.BREAKPOINT_SERVICE);
        if (EditorJavaLaunch.supports(project, item)) {
            getGutter().addColumn(new GutterColumn("java-launch", 15, this::launchMarker));
            launchChanges = multiPlainChanges().successionEnds(Duration.ofMillis(250))
                .subscribe(_ -> refreshLaunches());
            refreshLaunches();
        }
    }

    private void refreshLaunches() {
        long request = ++launchRequest;
        String source = getText();
        String filename = getFilePath().getFileName().toString();
        boolean tests = project.getGradleManager().isGradleProject();
        CompletableFuture.supplyAsync(() -> EditorJavaLaunch.find(source, filename).stream()
            .filter(launch -> tests || launch.testMethod() == null).toList())
            .thenAccept(found -> Platform.runLater(() -> {
                if (request == launchRequest && !isEditorClosed() && getText().equals(source)) {
                    parsedSource = source;
                    if (!launches.equals(found)) {
                        launches = found;
                        getGutter().refresh();
                    }
                }
            }));
    }

    private Node launchMarker(int line) {
        List<EditorJavaLaunch> targets = launches.stream().filter(launch -> launch.line() == line).toList();
        if (targets.isEmpty())
            return null;

        var marker = new FontIcon(FontAwesomeSolid.PLAY);
        marker.getStyleClass().add("editor-main-marker");
        var tooltip = new LocalizedTooltip("railroad.editor.launch.tooltip");
        marker.accessibleTextProperty().bind(tooltip.textProperty());
        Tooltip.install(marker, tooltip);
        marker.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || !getText().equals(parsedSource))
                return;

            var menu = new ContextMenu();
            for (EditorJavaLaunch launch : targets) {
                menu.getItems().addAll(launchAction(launch, false), launchAction(launch, true));
            }

            menu.show(marker, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        return marker;
    }

    private MenuItem launchAction(EditorJavaLaunch launch, boolean debug) {
        var item = new MenuItem(
            L18n.localize(debug ? "railroad.editor.launch.debug" : "railroad.editor.launch.run", launch.displayName()));
        item.setOnAction(_ -> {
            if (isEditorClosed() || !launches.contains(launch) || !getText().equals(parsedSource))
                return;

            RunConfiguration<?> configuration = launch.configuration(project, getFilePath());
            (debug ? RunCommands.DEBUG : RunCommands.RUN)
                .execute(CommandContext.withArgument(project, this, configuration));
        });

        return item;
    }

    @Override
    public void close() {
        super.close();
        if (isEditorClosed()) {
            launchChanges.unsubscribe();
            breakpointBinding.close();
        }
    }

    @Override
    protected boolean shouldRequestSignatureHelp(String text, int caret, boolean textChanged) {
        if (text == null || text.isEmpty() || caret < 0 || caret > text.length())
            return false;

        int openParen = findRelevantCallOpenParen(text, caret);
        if (openParen < 0)
            return false;

        if (textChanged)
            return true;

        if (!activeSignatureHelp.get().isEmpty())
            return true;

        if (caret == 0)
            return false;

        char previous = text.charAt(caret - 1);
        return previous == '(' || previous == ',' || Character.isJavaIdentifierPart(previous);
    }

    private static int findRelevantCallOpenParen(String text, int caret) {
        int start = Math.max(0, caret - SIGNATURE_SCAN_WINDOW);
        Deque<Integer> openParentheses = new ArrayDeque<>();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;

        for (int index = start; index < caret; index++) {
            char current = text.charAt(index);
            char next = index + 1 < caret ? text.charAt(index + 1) : '\0';

            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                }

                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                }

                continue;
            }

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }

                continue;
            }

            if (inChar) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '\'') {
                    inChar = false;
                }

                continue;
            }

            if (current == '/' && next == '/') {
                inLineComment = true;
                index++;
                continue;
            }

            if (current == '/' && next == '*') {
                inBlockComment = true;
                index++;
                continue;
            }

            if (current == '"') {
                inString = true;
                escaped = false;
                continue;
            }

            if (current == '\'') {
                inChar = true;
                escaped = false;
                continue;
            }

            if (current == '(') {
                openParentheses.addLast(index);
            } else if (current == ')' && !openParentheses.isEmpty()) {
                openParentheses.removeLast();
            }
        }

        while (!openParentheses.isEmpty()) {
            int candidate = openParentheses.removeLast();
            if (isCallablePrefix(text, candidate))
                return candidate;
        }

        return -1;
    }

    private static boolean isCallablePrefix(String text, int openParen) {
        int index = openParen - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }

        if (index < 0)
            return false;

        char current = text.charAt(index);
        if (current == ')' || current == ']' || current == '>')
            return true;

        if (!Character.isJavaIdentifierPart(current))
            return false;

        int start = index;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }

        String word = text.substring(start, index + 1);
        return !NON_CALLABLE_PREFIX_KEYWORDS.contains(word);
    }
}
