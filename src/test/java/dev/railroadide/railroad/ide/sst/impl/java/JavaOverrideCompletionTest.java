package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaOverrideCompletionTest {
    @Test
    public void typedPrefixInAClassBodySuggestsOverridableObjectMethods() {
        String source = """
            class Use {
                toS
            }
            """;
        CompletionResult result = completeAt(source, source.indexOf("toS"));
        assertNotNull(result);
        assertEquals(source.indexOf("toS"), result.replacementStart());
        List<String> names = result.items().stream().map(CompletionItem::insertText).toList();
        assertTrue(names.containsAll(List.of("clone", "equals", "hashCode", "toString")), names.toString());
        assertFalse(names.contains("getClass"));
        assertFalse(names.contains("wait"));
        assertFalse(names.contains("notify"));

        CompletionItem toString = item(result, "toString");
        assertEquals(CompletionItem.Kind.OVERRIDE, toString.kind());
        assertEquals("Object", toString.detail());
        assertEquals("@Override\npublic String toString() {\n\treturn super.toString();\n}",
            toString.template().text());
        assertEquals("@Override\npublic String toString() {\n\t".length(), toString.template().caretOffset());
        assertEquals("@Override\nprotected Object clone() throws CloneNotSupportedException {\n"
            + "\treturn super.clone();\n}", item(result, "clone").template().text());
        assertEquals("@Override\npublic boolean equals(Object object) {\n\treturn super.equals(object);\n}",
            item(result, "equals").template().text());
    }

    @Test
    public void explicitRequestOnAnEmptyMemberLineSuggestsGenericInterfaceMethods() {
        String source = """
            import java.util.Comparator;
            class Order implements Comparator<String> {
               \s
            }
            """;
        int caret = source.indexOf("   \n") + 3;
        CompletionResult result = completeAt(source, caret - 1);
        assertNotNull(result);
        assertEquals(caret, result.replacementStart());
        CompletionItem compare = item(result, "compare");
        assertEquals("(String, String)", compare.suffix());
        assertEquals("Comparator", compare.detail());
        assertEquals("@Override\npublic int compare(String string, String string2) {\n\treturn 0;\n}",
            compare.template().text());
        assertEquals("@Override\npublic boolean equals(Object object) {\n\treturn super.equals(object);\n}",
            item(result, "equals").template().text());
    }

    @Test
    public void sourceSupertypesSubstituteTypeArgumentsAndSkipFinalAndDeclaredMethods() {
        String source = """
            package demo;
            abstract class Base<T> {
                protected abstract <R extends T> R make(java.util.List<? extends T> items, int... counts)
                    throws Exception;
                public final void fixed() {}
                void packaged(T value) {}
                private void hidden() {}
                public static void utility() {}
            }
            class Impl extends Base<Integer> {
                @Override
                public String toString() { return ""; }
                fi
            }
            """;
        CompletionResult result = completeAt(source, source.indexOf("fi\n"));
        assertNotNull(result);
        List<String> names = result.items().stream().map(CompletionItem::insertText).toList();
        assertTrue(names.containsAll(List.of("make", "packaged", "equals")), names.toString());
        assertFalse(names.contains("fixed"));
        assertFalse(names.contains("hidden"));
        assertFalse(names.contains("utility"));
        assertFalse(names.contains("toString"));
        CompletionItem.Template make = item(result, "make").template();
        assertEquals("@Override\nprotected <R extends Integer> R make(List<? extends Integer> items, "
            + "int... counts) throws Exception {\n\treturn null;\n}", make.text());
        assertEquals(List.of(new CompletionItem.Insertion("package demo;".length(), "\n\nimport java.util.List;")),
            make.insertions());
        CompletionItem.Template packaged = item(result, "packaged").template();
        assertEquals("@Override\nvoid packaged(Integer value) {\n\tsuper.packaged(value);\n}", packaged.text());
        assertEquals(List.of(), packaged.insertions());
    }

    @Test
    public void unimportedTypesAreImportedInNameOrderAmongTheExistingImports() {
        String source = """
            package demo;

            import java.io.Closeable;
            import java.util.Set;

            abstract class Store extends java.util.AbstractMap<String, Integer> {
                ent
            }
            """;
        CompletionResult result = completeAt(source, source.indexOf("ent\n"));
        assertNotNull(result);
        CompletionItem.Template entrySet = item(result, "entrySet").template();
        assertEquals("@Override\npublic Set<Map.Entry<String, Integer>> entrySet() {\n\treturn null;\n}",
            entrySet.text());
        assertEquals(List.of(new CompletionItem.Insertion(source.indexOf("import java.util.Set;"),
            "import java.util.Map;\n")), entrySet.insertions());
    }

    @Test
    public void typesWhoseSimpleNameIsTakenStayQualified() {
        String source = """
            class List {}
            class Base { protected void take(java.util.List<String> items, java.util.Map<String, String> map) {} }
            class Impl extends Base {
                ta
            }
            """;
        CompletionResult result = completeAt(source, source.indexOf("ta\n"));
        assertNotNull(result);
        CompletionItem.Template take = item(result, "take").template();
        assertEquals("@Override\nprotected void take(java.util.List<String> items, Map<String, String> map) {\n"
            + "\tsuper.take(items, map);\n}", take.text());
        assertEquals(List.of(new CompletionItem.Insertion(0, "import java.util.Map;\n\n")), take.insertions());
    }

    @Test
    public void defaultMethodsOfDirectInterfacesDelegateThroughTheInterface() {
        String source = """
            interface Greeter {
                default String greet(String name) { return name; }
                void wave();
            }
            class Friendly implements Greeter {
                gr
            }
            """;
        CompletionResult result = completeAt(source, source.indexOf("gr\n"));
        assertNotNull(result);
        assertEquals("@Override\npublic String greet(String name) {\n\treturn Greeter.super.greet(name);\n}",
            item(result, "greet").template().text());
        assertEquals("@Override\npublic void wave() {\n\t\n}", item(result, "wave").template().text());
    }

    @Test
    public void positionsOutsideMemberDeclarationsSuggestNothing() {
        String inBody = "class A { void run() { toS } }";
        assertNull(completeAt(inBody, inBody.indexOf("toS")));
        String fieldName = "class A { int toS }";
        assertNull(completeAt(fieldName, fieldName.indexOf("toS")));
        String afterModifier = "class A { public toS }";
        assertNull(completeAt(afterModifier, afterModifier.indexOf("toS")));
        String topLevel = "toS";
        assertNull(completeAt(topLevel, 0));
    }

    private static CompletionResult completeAt(String source, int triggerAt) {
        return JavaSemanticCompletionEngine.compute(source, triggerAt, null);
    }

    private static CompletionItem item(CompletionResult result, String name) {
        return result.items().stream()
            .filter(item -> item.insertText().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + name + " in " + result.items()));
    }
}
