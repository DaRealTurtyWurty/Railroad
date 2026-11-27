package dev.railroadide.railroad.ide.ui;

import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.command.CommandContext;
import dev.railroadide.railroad.command.RunCommands;
import dev.railroadide.railroad.ide.runconfig.EditorJavaLaunch;
import dev.railroadide.railroad.ide.runconfig.RunConfiguration;
import dev.railroadide.railroad.ide.sst.impl.java.JavaLexer;
import dev.railroadide.railroad.ide.sst.impl.java.JavaSemanticAnalyzer;
import dev.railroadide.railroad.ide.sst.impl.java.JavaSyntaxKinds;
import dev.railroadide.railroad.ide.sst.impl.java.JavaSyntaxParser;
import dev.railroadide.railroad.ide.sst.impl.java.JavaTokenType;
import dev.railroadide.railroad.ide.sst.lexer.Token;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxKind;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxToken;
import dev.railroadide.railroad.ide.ui.codeeditor.BreakpointGutterBinding;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorPane;
import dev.railroadide.railroad.ide.ui.codeeditor.EditorFeatures;
import dev.railroadide.railroad.ide.ui.codeeditor.GutterColumn;
import dev.railroadide.railroad.ide.ui.codeeditor.JavaImportFolding;
import dev.railroadide.railroad.localization.L18n;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.settings.Settings;
import dev.railroadide.railroad.ui.localized.LocalizedTooltip;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import org.jspecify.annotations.Nullable;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.reactfx.Subscription;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Java editor behaviour: breakpoints, run and test markers for launchable code, import folding, block indentation,
 * and signature help requested from inside call arguments.
 */
public final class JavaEditorFeatures implements EditorFeatures {
    private static final String LAUNCH_COLUMN_ID = "java-launch";
    private static final int SIGNATURE_SCAN_WINDOW = 2048;
    private static final SyntaxKind SEMICOLON = JavaSyntaxKinds.tokenKind(JavaTokenType.SEMICOLON);
    private static final SyntaxKind CLOSE_PAREN = JavaSyntaxKinds.tokenKind(JavaTokenType.CLOSE_PAREN);
    private static final SyntaxKind ELSE_KEYWORD = JavaSyntaxKinds.tokenKind(JavaTokenType.ELSE_KEYWORD);
    private static final Set<SyntaxKind> SEMICOLON_STATEMENTS = Set.of(
        JavaSyntaxKinds.ASSERT_STATEMENT,
        JavaSyntaxKinds.BREAK_STATEMENT,
        JavaSyntaxKinds.CONTINUE_STATEMENT,
        JavaSyntaxKinds.DO_WHILE_STATEMENT,
        JavaSyntaxKinds.EMPTY_STATEMENT,
        JavaSyntaxKinds.EXPRESSION_STATEMENT,
        JavaSyntaxKinds.FIELD_DECLARATION,
        JavaSyntaxKinds.LOCAL_VARIABLE_DECLARATION_STATEMENT,
        JavaSyntaxKinds.RETURN_STATEMENT,
        JavaSyntaxKinds.THROW_STATEMENT,
        JavaSyntaxKinds.YIELD_STATEMENT);
    private static final Set<SyntaxKind> STATEMENT_OWNERS = Set.of(
        JavaSyntaxKinds.DO_WHILE_STATEMENT,
        JavaSyntaxKinds.FOR_STATEMENT,
        JavaSyntaxKinds.IF_STATEMENT,
        JavaSyntaxKinds.LABELED_STATEMENT,
        JavaSyntaxKinds.WHILE_STATEMENT);
    private static final Set<String> NON_CALLABLE_PREFIX_KEYWORDS = Set.of(
        "if",
        "for",
        "while",
        "switch",
        "catch",
        "synchronized",
        "try",
        "do");

    @Override
    public AutoCloseable install(CodeEditorPane editor) {
        return new Installation(editor);
    }

    @Override
    public boolean isIndentingBracket(String text, int bracketOffset) {
        if (text.charAt(bracketOffset) != '{')
            return false;

        Token<JavaTokenType> token = tokenAt(text, bracketOffset);
        return token != null && token.pos() == bracketOffset && token.type() == JavaTokenType.OPEN_BRACE;
    }

    @Override
    public boolean allowsBracketPairing(String text) {
        Token<JavaTokenType> token = tokenAt(text, text.length() - 1);
        return token != null && switch (token.type()) {
            case OPEN_BRACE, CLOSE_BRACE, OPEN_PAREN, CLOSE_PAREN, OPEN_BRACKET, CLOSE_BRACKET -> true;
            case STRING_LITERAL, TEXT_BLOCK_LITERAL -> token.lexeme().startsWith("\"")
                && Settings.AUTO_PAIR_INSIDE_STRINGS.getOrDefaultValue();
            default -> false;
        };
    }

    private static @Nullable Token<JavaTokenType> tokenAt(String text, int offset) {
        try (var lexer = new JavaLexer(text)) {
            while (true) {
                Token<JavaTokenType> token = lexer.nextToken();
                if (token.type() == JavaTokenType.EOF || token.pos() > offset)
                    return null;

                if (token.endOffset() > offset)
                    return token;
            }
        }
    }

    @Override
    public @Nullable NewlineIndentation newlineIndentation(String text, int offset) {
        int lineStart = text.lastIndexOf('\n', offset - 1) + 1;
        if (text.lastIndexOf(';', offset - 1) < lineStart && text.lastIndexOf(')', offset - 1) < lineStart)
            return null;

        SyntaxToken terminator = codeTokenBefore(JavaSyntaxParser.parse(text).root(), offset);
        if (terminator == null || terminator.start() < lineStart || terminator.end() > offset)
            return null;

        if (terminator.kind().equals(CLOSE_PAREN))
            return indentAfterControlHeader(terminator);

        if (!terminator.kind().equals(SEMICOLON))
            return null;

        return indentAfterStatement(terminator);
    }

    private static @Nullable NewlineIndentation indentAfterControlHeader(SyntaxToken terminator) {
        SyntaxNode header = terminator.parent().orElse(null);
        SyntaxNode owner = header == null ? null : header.parent().orElse(null);
        if (owner == null)
            return null;

        boolean controlHeader = (owner.kind().equals(JavaSyntaxKinds.IF_STATEMENT)
            || owner.kind().equals(JavaSyntaxKinds.WHILE_STATEMENT))
            && header.kind().equals(JavaSyntaxKinds.PARENTHESIZED_EXPRESSION)
            || owner.kind().equals(JavaSyntaxKinds.FOR_STATEMENT)
                && (header.kind().equals(JavaSyntaxKinds.BASIC_FOR_STATEMENT)
                    || header.kind().equals(JavaSyntaxKinds.ENHANCED_FOR_STATEMENT));
        if (!controlHeader)
            return null;

        SyntaxNode body = owner.children().stream()
            .filter(child -> child.start() >= header.end() && !(child instanceof SyntaxToken))
            .findFirst().orElse(null);
        int anchor = firstCodeOffset(owner);
        return new NewlineIndentation(anchor, body == null || !body.kind().equals(JavaSyntaxKinds.BLOCK));
    }

    private static @Nullable NewlineIndentation indentAfterStatement(SyntaxToken terminator) {
        SyntaxNode statement = terminator.parent().orElse(null);
        if (statement == null || !SEMICOLON_STATEMENTS.contains(statement.kind()))
            return null;

        SyntaxNode owner = statement.parent().orElse(null);
        if (owner != null && owner.kind().equals(JavaSyntaxKinds.BASIC_FOR_STATEMENT))
            return null;

        while (owner != null && STATEMENT_OWNERS.contains(owner.kind())) {
            statement = owner;
            if (statement.kind().equals(JavaSyntaxKinds.DO_WHILE_STATEMENT)
                || statement.kind().equals(JavaSyntaxKinds.IF_STATEMENT) && statement.children().stream()
                    .anyMatch(child -> child.start() > terminator.start() && child.kind().equals(ELSE_KEYWORD)))
                break;

            owner = statement.parent().orElse(null);
        }

        int anchor = firstCodeOffset(statement);
        return anchor < 0 ? null : new NewlineIndentation(anchor, false);
    }

    private static @Nullable SyntaxToken codeTokenBefore(SyntaxNode node, int offset) {
        if (node.start() >= offset || node.width() == 0)
            return null;

        if (node instanceof SyntaxToken token)
            return !JavaSemanticAnalyzer.isTriviaToken(token) || offset < token.end() && !token.text().isBlank()
                ? token
                : null;

        for (SyntaxNode child : node.children().reversed()) {
            SyntaxToken token = codeTokenBefore(child, offset);
            if (token != null)
                return token;
        }

        return null;
    }

    private static int firstCodeOffset(SyntaxNode node) {
        if (node instanceof SyntaxToken token)
            return token.width() == 0 || JavaSemanticAnalyzer.isTriviaToken(token) ? -1 : token.start();

        for (SyntaxNode child : node.children()) {
            int offset = firstCodeOffset(child);
            if (offset >= 0)
                return offset;
        }

        return -1;
    }

    @Override
    public boolean shouldRequestSignatureHelp(String text, int caret, boolean textChanged, boolean helpShown) {
        if (text == null || text.isEmpty() || caret < 0 || caret > text.length())
            return false;

        int openParen = findRelevantCallOpenParen(text, caret);
        if (openParen < 0)
            return false;

        if (textChanged || helpShown)
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

    private static final class Installation implements AutoCloseable {
        private final CodeEditorPane editor;
        private final Project project;
        private final BreakpointGutterBinding breakpoints;
        private final JavaImportFolding importFolding;
        private Subscription launchChanges = Subscription.EMPTY;
        private List<EditorJavaLaunch> launches = List.of();
        private @Nullable String parsedSource;
        private long launchRequest;
        private boolean closed;

        private Installation(CodeEditorPane editor) {
            this.editor = editor;
            project = editor.getProject();
            breakpoints = new BreakpointGutterBinding(editor.getGutter(), editor, editor::getFilePath,
                Services.BREAKPOINT_SERVICE);
            importFolding = new JavaImportFolding(editor);
            if (EditorJavaLaunch.supports(project, editor.getFilePath())) {
                editor.getGutter().addColumn(new GutterColumn(LAUNCH_COLUMN_ID, 15, this::launchMarker));
                launchChanges = editor.multiPlainChanges().successionEnds(Duration.ofMillis(250))
                    .subscribe(_ -> refreshLaunches());
                refreshLaunches();
            }
        }

        private void refreshLaunches() {
            long request = ++launchRequest;
            String source = editor.getText();
            String filename = editor.getFilePath().getFileName().toString();
            boolean tests = project.getGradleManager().isGradleProject();
            CompletableFuture.supplyAsync(() -> EditorJavaLaunch.find(source, filename).stream()
                .filter(launch -> tests || launch.testMethod() == null).toList())
                .thenAccept(found -> Platform.runLater(() -> {
                    if (request == launchRequest && !closed && editor.getText().equals(source)) {
                        parsedSource = source;
                        if (!launches.equals(found)) {
                            launches = found;
                            editor.getGutter().refresh();
                        }
                    }
                }));
        }

        private @Nullable Node launchMarker(int line) {
            List<EditorJavaLaunch> targets = launches.stream().filter(launch -> launch.line() == line).toList();
            if (targets.isEmpty())
                return null;

            var marker = new FontIcon(FontAwesomeSolid.PLAY);
            marker.getStyleClass().add("editor-main-marker");
            var tooltip = new LocalizedTooltip("railroad.editor.launch.tooltip");
            marker.accessibleTextProperty().bind(tooltip.textProperty());
            Tooltip.install(marker, tooltip);
            marker.setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY || !editor.getText().equals(parsedSource))
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
            var item = new MenuItem(L18n.localize(debug ? "railroad.editor.launch.debug" : "railroad.editor.launch.run",
                launch.displayName()));
            item.setOnAction(_ -> {
                if (closed || !launches.contains(launch) || !editor.getText().equals(parsedSource))
                    return;

                RunConfiguration<?> configuration = launch.configuration(project, editor.getFilePath());
                (debug ? RunCommands.DEBUG : RunCommands.RUN)
                    .execute(CommandContext.withArgument(project, editor, configuration));
            });

            return item;
        }

        @Override
        public void close() {
            if (closed)
                return;

            closed = true;
            launchChanges.unsubscribe();
            editor.getGutter().removeColumn(LAUNCH_COLUMN_ID);
            importFolding.close();
            breakpoints.close();
        }
    }
}
