package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaLambdaCompletionTest {
    @Test
    public void offersLambdaAtTheEmptyArgumentOfAGenericStreamCall() {
        String source = "class Example { void run(java.util.List<String> items) { items.stream().map(|); } }";
        assertEquals(List.of("value -> "), templates(source));
    }

    @Test
    public void completesMembersOfAnInferredLambdaParameter() {
        String source = "class Example { void run(java.util.List<String> items) { items.stream().map(item -> item.|); } }";
        CompletionResult result = complete(source);
        assertNotNull(result);
        assertTrue(result.items().stream().anyMatch(item -> item.insertText().equals("length")));
    }

    @Test
    public void choosesLambdasForTheCurrentSlotAndCompatibleOverloads() {
        String source = """
            class Example {
                interface Handler { void accept(int x, int y); default void reset() {} }
                void use(int key, Handler handler) {}
                void use(boolean key, Runnable action) {}
                void run() { use(%s, |); }
            }
            """;
        assertEquals(List.of("(value1, value2) -> "), templates(source.formatted("1")));
        assertEquals(List.of("() -> "), templates(source.formatted("true")));
    }

    @Test
    public void offersLambdasInConstructorsAndRepeatedVarargsSlots() {
        String source = """
            class Example {
                Example(Runnable... actions) {}
                void run() { new Example(() -> {},|); }
            }
            """;
        assertEquals(List.of("() -> "), templates(source));
    }

    @Test
    public void keepsLambdasOutOfOrdinaryOrAlreadyFilledArgumentSlots() {
        for (String source : List.of(
            "class Example { void run(int key) { run(|); } }",
            "class Example { void run(Runnable action) { run(|action); } }",
            "class Example { void run(Runnable action) { run(/* (| */); } }",
            "class Example { void run() { if (|) {} } }",
            """
                class Example {
                    abstract static class Handler { abstract void run(); }
                    void use(Handler handler) {}
                    void run() { use(|); }
                }
                """)) {
            assertEquals(List.of(), templates(source), source);
        }
    }

    private static List<String> templates(String source) {
        CompletionResult result = complete(source);
        if (result == null)
            return List.of();

        assertEquals(source.indexOf('|'), result.replacementStart());
        return result.items().stream().map(CompletionItem::template).map(template -> {
            assertNotNull(template);
            assertEquals(template.text().length(), template.caretOffset());
            return template.text();
        }).toList();
    }

    private static CompletionResult complete(String source) {
        return JavaSemanticCompletionEngine.compute(source.replace("|", ""), source.indexOf('|') - 1, null);
    }
}
