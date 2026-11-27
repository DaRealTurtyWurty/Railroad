package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaMethodCompletionTest {
    @Test
    public void completesCurrentClassMethodsAndTheirOverloadsFromTheFirstCharacter() {
        List<CompletionItem> items = methods("""
            class Example {
                void call() { r/*caret*/; }
                private int read() { return 0; }
                private int read(int index) { return index; }
                static void reset() {}
                class Nested { void retry() {} }
            }
            """);
        assertEquals(List.of("read", "read", "reset"), items.stream().map(CompletionItem::insertText).toList());
        assertEquals(List.of("()", "(int)", "()"), items.stream().map(CompletionItem::suffix).toList());
        assertEquals("int", items.getFirst().detail());
    }

    @Test
    public void omitsInstanceMethodsInStaticContexts() {
        String source = """
            class Example {
                static { p/*caret*/; }
                void process() {}
                static void publish() {}
            }
            """;
        assertEquals(List.of("publish"), methods(source).stream().map(CompletionItem::insertText).toList());
    }

    @Test
    public void completesAnUnqualifiedCallWithExistingParentheses() {
        List<CompletionItem> items = methods("""
            class Example {
                int caller() { return lo/*caret*/(1); }
                private int load(int index) { return index; }
            }
            """);
        assertEquals(List.of("load"), items.stream().map(CompletionItem::insertText).toList());
        assertTrue(items.getFirst().hasParameters());
    }

    @Test
    public void completesANewStatementBeforeAnExistingCall() {
        String source = """
            class Example {
                void caller() {
                    loa/*caret*/
                    %s
                }
                private void load() {}
                private void save() {}
            }
            """;
        for (String call : List.of("save();", "System.out.println();")) {
            assertEquals(List.of("load"),
                methods(source.formatted(call)).stream().map(CompletionItem::insertText).toList(), call);
        }
    }

    @Test
    public void omitsMethodsFromQualifiedSelectorsAndTypePositions() {
        for (String statement : List.of("this.lo/*caret*/();", "lo/*caret*/ value;", "// lo/*caret*/\n")) {
            assertTrue(methods("class Example { void load() {} void caller() { " + statement + " } }").isEmpty());
        }
    }

    private static List<CompletionItem> methods(String document) {
        int caret = document.indexOf("/*caret*/");
        var result = JavaSemanticCompletionEngine.compute(document.replace("/*caret*/", ""), caret - 1, null);
        return result == null
            ? List.of()
            : result.items().stream()
                .filter(item -> item.kind() == CompletionItem.Kind.METHOD)
                .toList();
    }
}
