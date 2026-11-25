package dev.railroadide.railroad.ide.syntaxhighlighting;

import groovyjarjarantlr4.v4.runtime.CharStreams;
import groovyjarjarantlr4.v4.runtime.Token;
import org.apache.groovy.parser.antlr4.GroovyLangLexer;
import org.apache.groovy.parser.antlr4.GroovyLexer;
import org.apache.groovy.parser.antlr4.GroovySyntaxError;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.List;

/**
 * Highlights Groovy source using the Groovy compiler's own lexer, so keywords, strings, GStrings, slashy strings and
 * comments are recognized exactly as Groovy reads them.
 * <p>
 * The lexer stops at the first lexical error, which is common while typing, for example in an unterminated string,
 * by throwing a {@link GroovySyntaxError} (an {@link AssertionError}). Highlighting then resumes on the following
 * line, leaving the rest of the failing line unstyled.
 */
public final class GroovySyntaxHighlighting {
    private static final int MAX_RESTARTS = 256;

    private GroovySyntaxHighlighting() {
    }

    /**
     * Computes spans in JavaFX's UTF-16 coordinates covering the entire text, including incomplete code.
     *
     * @param text Groovy source text to highlight
     * @return syntax style classes covering the entire text
     */
    public static StyleSpans<Collection<String>> computeHighlighting(String text) {
        var spans = new StyleSpansBuilder<Collection<String>>();
        int styledTo = 0;
        int base = 0;
        int restarts = 0;
        while (base < text.length()) {
            String chunk = text.substring(base);
            int[] offsets = utf16Offsets(chunk);
            var lexer = new GroovyLangLexer(CharStreams.fromString(chunk));
            lexer.removeErrorListeners();
            int previousType = Token.INVALID_TYPE;
            try {
                for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
                    String style = style(token, previousType);
                    previousType = token.getType();
                    if (style == null)
                        continue;

                    int start = base + utf16(offsets, token.getStartIndex());
                    int end = base + utf16(offsets, token.getStopIndex() + 1);
                    if (start < styledTo || end <= start)
                        continue;

                    spans.add(List.of(), start - styledTo);
                    spans.add(List.of(style), end - start);
                    styledTo = end;
                }

                break;
            } catch (RuntimeException | GroovySyntaxError lexicalError) {
                int failedAt = base + utf16(offsets, Math.max(0, lexer._tokenStartCharIndex));
                int lineEnd = text.indexOf('\n', Math.max(failedAt, styledTo));
                if (lineEnd < 0 || ++restarts > MAX_RESTARTS)
                    break;

                base = lineEnd + 1;
            }
        }

        spans.add(List.of(), text.length() - styledTo);
        return spans.create();
    }

    private static String style(Token token, int previousType) {
        int type = token.getType();
        if (type == GroovyLexer.NL) {
            String text = token.getText();
            return text.startsWith("//") || text.startsWith("/*") ? "comment" : null;
        }

        if (previousType == GroovyLexer.AT
            && (type == GroovyLexer.Identifier || type == GroovyLexer.CapitalizedIdentifier))
            return "annotation";

        // The lexer numbers keywords contiguously from AS to YIELD, and operators and punctuation from
        // RANGE_INCLUSIVE to ELVIS_ASSIGN.
        if ((type >= GroovyLexer.AS && type <= GroovyLexer.YIELD) || type == GroovyLexer.BooleanLiteral
            || type == GroovyLexer.NullLiteral || type == GroovyLexer.NOT_INSTANCEOF || type == GroovyLexer.NOT_IN)
            return "keyword";

        return switch (type) {
            case GroovyLexer.StringLiteral, GroovyLexer.GStringBegin, GroovyLexer.GStringPart, GroovyLexer.GStringEnd,
                GroovyLexer.GStringPathPart -> "string";
            case GroovyLexer.IntegerLiteral, GroovyLexer.FloatingPointLiteral -> "number";
            case GroovyLexer.CapitalizedIdentifier -> "type";
            case GroovyLexer.AT -> "annotation";
            case GroovyLexer.SH_COMMENT -> "comment";
            case GroovyLexer.LPAREN, GroovyLexer.RPAREN, GroovyLexer.LBRACE, GroovyLexer.RBRACE, GroovyLexer.LBRACK,
                GroovyLexer.RBRACK, GroovyLexer.SEMI, GroovyLexer.COMMA, GroovyLexer.DOT, GroovyLexer.ELLIPSIS ->
                "punctuation";
            default -> type >= GroovyLexer.RANGE_INCLUSIVE && type <= GroovyLexer.ELVIS_ASSIGN ? "operator" : null;
        };
    }

    /**
     * Maps the lexer's code point offsets to UTF-16 offsets, or returns {@code null} when they coincide.
     */
    private static int[] utf16Offsets(String text) {
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints == text.length())
            return null;

        int[] offsets = new int[codePoints + 1];
        int utf16 = 0;
        for (int codePoint = 0; codePoint < codePoints; codePoint++) {
            offsets[codePoint] = utf16;
            utf16 += Character.charCount(text.codePointAt(utf16));
        }

        offsets[codePoints] = utf16;
        return offsets;
    }

    private static int utf16(int[] offsets, int codePoint) {
        if (offsets == null)
            return codePoint;

        return offsets[Math.min(codePoint, offsets.length - 1)];
    }
}
