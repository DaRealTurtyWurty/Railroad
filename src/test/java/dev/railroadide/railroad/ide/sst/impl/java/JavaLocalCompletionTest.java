package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaLocalCompletionTest {
    @Test
    public void completesDeclaredAndInheritedFieldsWithoutAQualifier() {
        List<CompletionItem> items = values("""
            class Parent {
                protected int valueInherited;
                private int valuePrivate;
            }
            class Example extends Parent {
                int read() { return v/*caret*/; }
                private long valueOwn;
                static int valueShared;
            }
            """);
        assertEquals(List.of("valueInherited", "valueOwn", "valueShared"),
            items.stream().map(CompletionItem::insertText).toList());
        assertEquals("long", items.get(1).detail());
    }

    @Test
    public void omitsInstanceFieldsInStaticContextsAndKeepsShadowingLocals() {
        String source = """
            class Example {
                private String valueInstance;
                private static int valueShared;
                private static int valueShadowed;
                static int read() { long valueShadowed = 1; return v/*caret*/; }
            }
            """;
        List<CompletionItem> items = values(source);
        assertEquals(List.of("valueShadowed", "valueShared"),
            items.stream().map(CompletionItem::insertText).toList());
        assertEquals("long", items.getFirst().detail());
    }

    @Test
    public void completesStaticImportsAndImplicitInterfaceFields() {
        List<CompletionItem> items = values("""
            import static java.lang.Integer.MAX_VALUE;
            import static java.lang.Long.*;
            interface Limits { int MAX_COUNT = 1; }
            class Example implements Limits {
                static long read() { return MAX/*caret*/; }
            }
            """);
        assertEquals(List.of("MAX_COUNT", "MAX_VALUE"), items.stream().map(CompletionItem::insertText).toList());
        assertEquals("int", items.getLast().detail());
        assertEquals(List.of("MIN_VALUE"), values("""
            import static java.lang.Long.*;
            class Example { long read() { return MI/*caret*/; } }
            """).stream().map(CompletionItem::insertText).toList());
    }

    @Test
    public void completesAnAccessibleBinaryFieldFromAnInheritedType() {
        List<CompletionItem> items = values("""
            class Example extends java.io.FilterInputStream {
                public int read() { return in/*caret*/; }
            }
            """);
        assertEquals(List.of("in"), items.stream().map(CompletionItem::insertText).toList());
        assertEquals("java.io.InputStream", items.getFirst().detail());
    }

    @Test
    public void completesOnlyStaticFieldsOfTheOuterTypeAcrossAStaticNestedClass() {
        List<CompletionItem> items = values("""
            class Outer {
                int valueOuter;
                static int valueStatic;
                static class Nested {
                    int valueInner;
                    int read() { return v/*caret*/; }
                }
            }
            """);
        assertEquals(List.of("valueInner", "valueStatic"), items.stream().map(CompletionItem::insertText).toList());
    }

    @Test
    public void completesParametersAndEarlierLocalsFromEnclosingBlocks() {
        String document = """
            class Example {
                void other() { int countOtherMethod = 0; }
                void run(int countParameter) {
                    { int countSibling = 0; }
                    var countOuter = 1;
                    {
                        long countInner = 2;
                        use(co/*caret*/);
                        int countLater = 3;
                    }
                }
                void use(int value) {}
            }
            """;
        List<CompletionItem> items = values(document);
        assertEquals(List.of("countInner", "countOuter", "countParameter"),
            items.stream().map(CompletionItem::insertText).toList());
        assertEquals(List.of("long", "int", "int"), items.stream().map(CompletionItem::detail).toList());
    }

    @Test
    public void completesPatternVariablesOnlyWhereThePatternMatched() {
        String source = """
            class Example {
                void run(Object value) {
                    if (value instanceof String matchedText) {
                        use(%s);
                    } else {
                        use(%s);
                    }
                }
                void use(Object value) {}
            }
            """;
        assertEquals(List.of("matchedText"),
            values(source.formatted("ma/*caret*/", "null")).stream().map(CompletionItem::insertText).toList());
        assertTrue(values(source.formatted("null", "ma/*caret*/")).isEmpty());
    }

    @Test
    public void completesLambdaParametersAtTheFirstCharacter() {
        String document = """
            class Example {
                java.util.function.IntUnaryOperator increment() {
                    return lambdaValue -> l/*caret*/ + 1;
                }
            }
            """;
        assertEquals(List.of("lambdaValue"), values(document).stream().map(CompletionItem::insertText).toList());
    }

    @Test
    public void completesAnEnhancedForVariableOnlyInTheLoopBody() {
        String source = """
            class Example {
                void run() {
                    for (String itemValue : %s) {
                        use(%s);
                    }
                }
                void use(Object value) {}
            }
            """;
        assertEquals(List.of("itemValue"),
            values(source.formatted("new String[0]", "it/*caret*/")).stream().map(CompletionItem::insertText).toList());
        assertTrue(values(source.formatted("it/*caret*/", "null")).isEmpty());
    }

    @Test
    public void completesATryResourceOnlyInTheTryBody() {
        String source = """
            class Example {
                void run() throws Exception {
                    try (AutoCloseable resourceValue = () -> {}) {
                        use(%s);
                    } finally {
                        use(%s);
                    }
                }
                void use(Object value) {}
            }
            """;
        assertEquals(List.of("resourceValue"),
            values(source.formatted("re/*caret*/", "null")).stream().map(CompletionItem::insertText).toList());
        assertTrue(values(source.formatted("null", "re/*caret*/")).isEmpty());
    }

    @Test
    public void omitsLocalsInCommentsStringsAndTypePositions() {
        for (String statement : List.of("// lo/*caret*/\n", "use(\"lo/*caret*/\");", "lo/*caret*/ variable;")) {
            assertTrue(values("class Example { void run() { int localValue = 1; " + statement + "} }").isEmpty());
        }
    }

    private static List<CompletionItem> values(String document) {
        int caret = document.indexOf("/*caret*/");
        String source = document.replace("/*caret*/", "");
        var result = JavaSemanticCompletionEngine.compute(source, caret - 1, null);
        if (result == null)
            return List.of();

        assertEquals(identifierStart(source, caret), result.replacementStart());
        return result.items().stream().filter(item -> item.kind() == CompletionItem.Kind.FIELD).toList();
    }

    private static int identifierStart(String source, int caret) {
        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
            start--;
        }

        return start;
    }
}
