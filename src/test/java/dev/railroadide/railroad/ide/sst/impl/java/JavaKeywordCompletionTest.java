package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaKeywordCompletionTest {
    @Test
    public void suggestsStatementKeywordsAtTheStartOfAStatement() {
        assertKeyword("class Example { int run() { ret| } }", "ret", "return");
        assertKeyword("class Example { void run() { i| } }", "i", "if");
        assertKeyword("class Example { void run() { fo| } }", "fo", "for");
        assertKeyword("class Example { int run(boolean ready) { if (ready) ret| } }", "ret", "return");
        assertKeyword("class Example { void run() { new String[; fo| } }", "fo", "for");
    }

    @Test
    public void suggestsKeywordsForDeclarationsAndExpressionOperands() {
        assertKeyword("rec|", "rec", "record");
        assertKeyword("class Example { pri| }", "pri", "private");
        assertKeyword("class Example { boolean run() { return tr|; } }", "tr", "true");
        assertKeyword("class Example { Object value = nu|; }", "nu", "null");
    }

    @Test
    public void keepsKeywordsOutOfMemberSelectorsCommentsAndStrings() {
        for (String source : List.of(
            "class Example { void run() { this.| } }",
            "class Example { void run() { this.ret| } }",
            "class Example { void run() { this. /* comment */ ret| } }",
            "class Example { void run() { this::re| } }",
            "class Example { void run() { // ret|\n } }",
            "class Example { void run() { String value = \"ret|\"; } }")) {
            CompletionResult result = complete(source);
            assertTrue(result == null || result.items().stream()
                .noneMatch(item -> item.kind() == CompletionItem.Kind.KEYWORD), source);
        }
    }

    @Test
    public void doesNotSuggestStatementKeywordsInArgumentsOrClassBodies() {
        for (String source : List.of("class Example { void run() { use(ret|); } }", "class Example { ret| }")) {
            CompletionResult result = complete(source);
            assertTrue(result == null || !result.items().contains(CompletionItem.keyword("return")), source);
        }
    }

    @Test
    public void returnsKeywordsAlongsideMatchingSymbols() {
        CompletionResult result = complete("class Example { int run() { int result = 0; re| } }");
        assertNotNull(result);
        assertTrue(result.items().contains(CompletionItem.keyword("return")));
        assertTrue(result.items().stream()
            .anyMatch(item -> item.kind() == CompletionItem.Kind.FIELD && item.insertText().equals("result")));
    }

    private static void assertKeyword(String source, String prefix, String keyword) {
        CompletionResult result = complete(source);
        assertNotNull(result, source);
        assertEquals(source.indexOf('|') - prefix.length(), result.replacementStart());
        assertTrue(result.items().contains(CompletionItem.keyword(keyword)), source);
    }

    private static CompletionResult complete(String source) {
        int caret = source.indexOf('|');
        return JavaSemanticCompletionEngine.compute(source.replace("|", ""), caret - 1, null);
    }
}
