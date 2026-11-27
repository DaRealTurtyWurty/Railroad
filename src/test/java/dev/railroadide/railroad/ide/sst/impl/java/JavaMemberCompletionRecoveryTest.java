package dev.railroadide.railroad.ide.sst.impl.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaMemberCompletionRecoveryTest {
    @Test
    public void completesMembersAfterAnEarlierSyntaxError() {
        assertAll(List.of(
            "void broken() { int number = 1 }",
            "void broken() { helper(1; }",
            "void broken() { helper(1 }",
            "void broken(int number { }",
            "void broken(int number;",
            "void broken() { String value = \"unterminated\n; }",
            "void broken() { helper(1);").stream().map(earlier -> (Executable) () -> {
                String document = "class Example {\n" + earlier + "\n"
                    + "void later(String text) { text.sub; }\n"
                    + "void helper(int number) {}\n}";
                int dot = document.indexOf("text.sub") + "text".length();
                assertTrue(JavaSyntaxParser.parseWithDiagnostics(document).diagnostics().stream()
                    .anyMatch(issue -> issue.startOffset() < dot), earlier);
                var result = JavaSemanticCompletionEngine.compute(document, dot, null);
                assertNotNull(result, earlier);
                assertEquals(dot + 1, result.replacementStart(), earlier);
                assertTrue(result.items().stream().anyMatch(item -> item.insertText().equals("substring")), earlier);
            }));
    }

    @Test
    public void keepsLocalRecordsAndContextualIdentifiersInsideTheMethodBody() {
        String document = """
            class Example {
                void run() {
                    record Local(int number) {}
                    Local value = new Local(1);
                    int when = value.number();
                    System.out.println(when);
                }
            }
            """;
        assertTrue(JavaSyntaxParser.parseWithDiagnostics(document).diagnostics().isEmpty());
    }
}
