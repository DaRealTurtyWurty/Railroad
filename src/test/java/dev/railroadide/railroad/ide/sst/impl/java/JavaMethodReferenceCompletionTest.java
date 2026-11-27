package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaMethodReferenceCompletionTest {
    @Test
    public void completesBoundReferencesOnThisAndOnAVariable() {
        for (String receiver : List.of("this", "other")) {
            List<CompletionItem> items = complete("""
                class Example {
                    Runnable bind(Example other) { return %s::/*caret*/; }
                    private void load() {}
                    static void reload() {}
                    int loading;
                }
                """.formatted(receiver));
            assertTrue(items.stream().anyMatch(item -> item.insertText().equals("load")
                && item.kind() == CompletionItem.Kind.METHOD && "load".equals(item.template().text())));
            assertFalse(
                items.stream().anyMatch(item -> List.of("reload", "loading", "new").contains(item.insertText())));
        }
    }

    @Test
    public void completesStaticAndUnboundInstanceReferencesOnAType() {
        List<CompletionItem> items = complete("""
            class Example {
                Object bind() { return Example::/*caret*/; }
                private void load() {}
                static void reload() {}
            }
            """);
        assertTrue(
            items.stream().map(CompletionItem::insertText).toList().containsAll(List.of("load", "reload", "new")));
    }

    @Test
    public void completesInheritedMethodsOnABinaryReceiver() {
        List<CompletionItem> items = complete("""
            class Example {
                Object bind(java.io.ByteArrayInputStream stream) { return stream::/*caret*/; }
            }
            """);
        assertTrue(items.stream().anyMatch(item -> "transferTo".equals(item.insertText())));
        assertFalse(items.stream().anyMatch(item -> "nullInputStream".equals(item.insertText())));
        assertFalse(items.stream().anyMatch(item -> "clone".equals(item.insertText())));
    }

    @Test
    public void completesArrayConstructorReferences() {
        for (String type : List.of("int[]", "String[][]")) {
            List<CompletionItem> items = complete("class Example { Object bind() { return " + type
                + "::/*caret*/; } }");
            assertEquals(List.of("new"), items.stream().map(CompletionItem::insertText).toList());
            assertEquals("(int)", items.getFirst().suffix());
            assertEquals(type, items.getFirst().detail());
        }
    }

    @Test
    public void completesAReferenceNameAfterExplicitTypeArguments() {
        String document = """
            class Example {
                java.util.function.Function<String, String> bind() { return this::<String> ec; }
                private <T> T echo(T value) { return value; }
            }
            """;
        int start = document.indexOf(" ec;") + 1;
        var result = JavaSemanticCompletionEngine.compute(document, start + 1, null);
        assertNotNull(result);
        assertEquals(start, result.replacementStart());
        assertTrue(result.items().stream().anyMatch(item -> "echo".equals(item.insertText())));
    }

    @Test
    public void completesConstructorsOnlyWhenAccessibleAndInstantiable() {
        List<CompletionItem> items = complete("""
            class Example { Object bind() { return Target::/*caret*/; } }
            class Target {
                private Target() {}
                Target(int count) {}
            }
            """);
        assertEquals(List.of("(int)"), items.stream().filter(item -> "new".equals(item.insertText()))
            .map(CompletionItem::suffix).toList());

        for (String target : List.of("interface Target {}", "abstract class Target {}", "enum Target { ITEM }",
            "class Target { private Target() {} }")) {
            assertFalse(complete("class Example { Object bind() { return Target::/*caret*/; } } " + target)
                .stream().anyMatch(item -> "new".equals(item.insertText())), target);
        }
    }

    @Test
    public void doesNotTreatColonsInCommentsOrStringsAsAReference() {
        assertTrue(complete("class Example { /* this::/*caret*/ */ void load() {} }").isEmpty());
        assertTrue(complete("class Example { String text = \"this::/*caret*/\"; void load() {} }").isEmpty());
    }

    private static List<CompletionItem> complete(String document) {
        int caret = document.indexOf("/*caret*/");
        String source = document.replace("/*caret*/", "");
        var result = JavaSemanticCompletionEngine.compute(source, caret - 1, null);
        if (result == null)
            return List.of();

        assertEquals(caret, result.replacementStart());
        return result.items();
    }
}
