package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.sst.project.JavaLibrarySymbolIndex;
import dev.railroadide.railroad.ide.sst.project.JavaProjectSemanticIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import dev.railroadide.railroad.ide.sst.project.JavaProjectSemanticIndex;

import static org.junit.jupiter.api.Assertions.*;

public class JavaTypeCompletionTest {
    @Test
    public void completesAnUnimportedTypeFromItsCamelCaseAbbreviation() {
        String document = "package demo;\n\nclass Example { ArrLst field; }\n";
        int start = document.indexOf("ArrLst");
        var result = JavaSemanticCompletionEngine.compute(document, start + "ArrLst".length() - 1, null);
        assertNotNull(result);
        assertEquals(start, result.replacementStart());
        CompletionItem item = result.items().stream()
            .filter(candidate -> candidate.insertText().equals("ArrayList") && candidate.detail().equals("java.util"))
            .findFirst().orElseThrow();
        assertEquals("ArrayList", item.template().text());
        assertEquals(List.of(new CompletionItem.Insertion("package demo;".length(),
            "\n\nimport java.util.ArrayList;")), item.template().insertions());
    }

    @Test
    public void completesMapEntryAfterTheQualifierInBothTypeAndExpressionPositions() {
        for (String document : List.of("""
            import java.util.Map;
            class Example { Map.En field; }
            """, """
            import java.util.Map;
            class Example { Object read() { return Map.En; } }
            """)) {
            int dot = document.indexOf("Map.En") + "Map".length();
            var result = JavaSemanticCompletionEngine.compute(document, dot, null);
            assertNotNull(result);
            assertEquals(dot + 1, result.replacementStart());
            assertTrue(result.items().stream().anyMatch(item -> item.kind() == CompletionItem.Kind.TYPE
                && item.insertText().equals("Entry") && item.template() == null), document);
        }
    }

    @Test
    public void importsABareNestedBinaryTypeUsingItsJavaSourceName() {
        String document = "package demo;\n\nclass Example { En field; }\n";
        CompletionItem item = find(document, "En field", "Entry", "java.util.Map");
        assertEquals("Entry", item.template().text());
        assertEquals(List.of(new CompletionItem.Insertion("package demo;".length(),
            "\n\nimport java.util.Map.Entry;")), item.template().insertions());
    }

    @Test
    public void doesNotAddAnotherImportForAnImportedNestedType() {
        String document = "import java.util.Map.Entry;\nclass Example { En field; }";
        CompletionItem item = find(document, "En field", "Entry", "java.util.Map");
        assertEquals("Entry", item.template().text());
        assertTrue(item.template().insertions().isEmpty());
    }

    @Test
    public void completesSourceMemberClassesInsideTheirOwner() {
        String document = """
            package demo;
            class Outer {
                private class Member {}
                Me bare;
                Outer.Me qualified;
            }
            """;
        CompletionItem bare = find(document, "Me bare", "Member", "demo.Outer");
        assertEquals("Member", bare.template().text());
        assertTrue(bare.template().insertions().isEmpty());
        int dot = document.indexOf("Outer.Me") + "Outer".length();
        var qualified = JavaSemanticCompletionEngine.compute(document, dot, null);
        assertNotNull(qualified);
        assertTrue(qualified.items().stream().anyMatch(item -> item.kind() == CompletionItem.Kind.TYPE
            && item.insertText().equals("Member")));
    }

    @Test
    public void qualifiesNestedTypesThatConflictWithAnExistingImport() {
        String document = "import java.security.KeyStore.Entry;\nclass Example { En field; }";
        CompletionItem item = find(document, "En field", "Entry", "java.util.Map");
        assertEquals("java.util.Map.Entry", item.template().text());
        assertTrue(item.template().insertions().isEmpty());
    }

    @Test
    public void completesMemberTypesFromOtherWorkspaceSources(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("Outer.java"), """
            package pkg;
            public class Outer {
                public static class PublicNested {}
                private static class PrivateNested {}
            }
            """);
        JavaProjectSemanticIndex index = new JavaProjectSemanticIndexer().build(directory);
        String document = "package demo;\nimport pkg.Outer;\nclass Example { Outer.Pu field; Pu other; }";
        int dot = document.indexOf("Outer.Pu") + "Outer".length();
        var qualified = JavaSemanticCompletionEngine.compute(document, dot, index);
        assertNotNull(qualified);
        assertEquals(List.of("PublicNested"),
            qualified.items().stream().map(CompletionItem::insertText).toList());

        var bare = JavaSemanticCompletionEngine.compute(document, document.lastIndexOf("Pu") + 1, index);
        assertNotNull(bare);
        CompletionItem item = bare.items().stream().filter(candidate -> "PublicNested".equals(candidate.insertText()))
            .findFirst().orElseThrow();
        assertTrue(item.template().insertions().getFirst().text().contains("import pkg.Outer.PublicNested;"));
    }

    @Test
    public void excludesHiddenAndLocalMemberClassesFromBinarySuggestions(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("Outer.java");
        Files.writeString(file, """
            package pkg;
            public class Outer {
                public static class Available {}
                private static class Hidden {
                    public static class HiddenBehindOwner {}
                }
                protected static class HiddenProtected {}
                public Class<?> localType() {
                    class HiddenLocal {}
                    return HiddenLocal.class;
                }
            }
            """);
        Path classes = Files.createDirectory(directory.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
            classes.toString(), file.toString()));
        var index = JavaLibrarySymbolIndex.build(List.of(classes));
        String document = "package demo;\nimport pkg.Outer;\nclass Example { Outer.Ava field; Hid unrelated; }";
        var qualified = JavaSemanticCompletionEngine.compute(document, document.indexOf("Outer.Ava") + "Outer".length(),
            index);
        assertNotNull(qualified);
        assertEquals(List.of("Available"), qualified.items().stream().map(CompletionItem::insertText).toList());
        var unqualified = JavaSemanticCompletionEngine.compute(document, document.indexOf("Hid") + 1, index);
        assertTrue(unqualified == null || unqualified.items().stream()
            .noneMatch(item -> item.kind() == CompletionItem.Kind.TYPE));
    }

    @Test
    public void completesADependencyTypeBeforeAnyMemberAccess(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("Fluids.java");
        Files.writeString(file, "package net.minecraft.world.level.material; public class Fluids {}");
        Path classes = Files.createDirectory(directory.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
            classes.toString(), file.toString()));
        var index = JavaLibrarySymbolIndex.build(List.of(classes));
        String document = "package p;\n\nclass A { void run() { Fluids } }\n";
        int start = document.indexOf("Fluids");
        var result = JavaSemanticCompletionEngine.compute(document, start + 1, index);
        assertNotNull(result);
        assertEquals(start, result.replacementStart());
        CompletionItem suggested = result.items().getFirst();
        assertEquals("Fluids", suggested.insertText());
        assertEquals(List.of(new CompletionItem.Insertion("package p;".length(),
            "\n\nimport net.minecraft.world.level.material.Fluids;")), suggested.template().insertions());
    }

    @Test
    public void omitsTheImportForATypeAlreadyInScope() {
        String document = "package demo;\nimport java.util.*;\nclass Main { ArrayL }\n";
        CompletionItem item = find(document, "ArrayL", "ArrayList", "java.util");
        assertEquals("ArrayList", item.template().text());
        assertTrue(item.template().insertions().isEmpty());
    }

    @Test
    public void keepsAConflictingTypeQualifiedWithoutChangingExistingImports() {
        String document = "package demo;\nimport java.sql.Date;\nclass Main { Da date; }\n";
        CompletionItem item = find(document, "Da date", "Date", "java.util");
        assertEquals("java.util.Date", item.template().text());
        assertTrue(item.template().insertions().isEmpty());
    }

    @Test
    public void offersNeitherTypeImportsInCommentsNorTypesAsUnqualifiedMethodNames() {
        for (String document : new String[]{"class Main { // ArrayL\n }", "class Main { void run() { ArrayL(); } }"}) {
            int trigger = document.indexOf("ArrayL") + 1;
            assertNull(JavaSemanticCompletionEngine.compute(document, trigger, null));
        }
    }

    private static CompletionItem find(String document, String marker, String name, String packageName) {
        int start = document.indexOf(marker);
        var result = JavaSemanticCompletionEngine.compute(document, start + 1, null);
        assertNotNull(result);
        assertEquals(start, result.replacementStart());
        return result.items().stream().filter(item -> item.kind() == CompletionItem.Kind.TYPE
            && name.equals(item.insertText()) && packageName.equals(item.detail())).findFirst().orElseThrow();
    }
}
