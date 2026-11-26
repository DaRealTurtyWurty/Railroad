package dev.railroadide.railroad.ide.language.impl.groovy;

import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.ui.codeeditor.BreakpointGutterBinding;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorPane;
import dev.railroadide.railroad.ide.ui.codeeditor.EditorFeatures;
import groovyjarjarantlr4.v4.runtime.CharStreams;
import groovyjarjarantlr4.v4.runtime.Token;
import org.apache.groovy.parser.antlr4.GroovyLangLexer;
import org.apache.groovy.parser.antlr4.GroovyLexer;
import org.apache.groovy.parser.antlr4.GroovySyntaxError;

/**
 * Groovy editor behaviour: block and collection indentation, plus a breakpoint gutter.
 */
public final class GroovyEditorFeatures implements EditorFeatures {
    @Override
    public AutoCloseable install(CodeEditorPane editor) {
        return new BreakpointGutterBinding(editor.getGutter(), editor, editor::getFilePath,
            Services.BREAKPOINT_SERVICE);
    }

    @Override
    public boolean isIndentingBracket(String text, int bracketOffset) {
        char bracket = text.charAt(bracketOffset);
        if (bracket != '{' && bracket != '[')
            return false;

        int offset = text.codePointCount(0, bracketOffset);
        var input = CharStreams.fromString(text);
        var lexer = new GroovyLangLexer(input);
        lexer.removeErrorListeners();
        try {
            for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
                // The Groovy lexer emits a division token at the start of an unterminated block comment.
                if (token.getType() == GroovyLexer.DIV && input.LA(1) == '*')
                    return false;

                if (token.getStopIndex() >= offset)
                    return token.getStartIndex() == offset
                        && (token.getType() == GroovyLexer.LBRACE || token.getType() == GroovyLexer.LBRACK);
            }
        } catch (RuntimeException | GroovySyntaxError lexicalError) {
            return false;
        }

        return false;
    }
}
