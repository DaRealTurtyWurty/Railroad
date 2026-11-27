package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.sst.project.JavaLibrarySymbolIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaTypeCompletionTest {
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
