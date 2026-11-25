package dev.railroadide.railroad.ide.diagnostics;

import com.google.common.collect.ImmutableList;
import dev.railroadide.railroad.ide.diagnostics.EditorDiagnostic.TextEditorDiagnostic;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.document.api.DocumentOrigin;
import dev.railroadide.railroad.ide.sst.document.api.DocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.DocumentUri;
import dev.railroadide.railroad.ide.sst.document.api.DocumentVersion;
import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import dev.railroadide.railroad.ide.sst.request.api.CancellationToken;
import dev.railroadide.railroad.ide.sst.request.api.Deadline;
import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class GroovyDiagnosticsProviderTest {
    @TempDir
    public Path directory;
    private final GroovyDiagnosticsProvider provider = new GroovyDiagnosticsProvider();

    @AfterEach
    public void closeProvider() {
        provider.close();
    }

    @Test
    public void reportsAnUnknownTypeAtItsReference() {
        String text = "class Example {\n    Missing value\n}\n";
        List<TextEditorDiagnostic> diagnostics = diagnose(text, Map.of());

        assertEquals(1, diagnostics.size(), diagnostics.toString());
        TextEditorDiagnostic diagnostic = diagnostics.getFirst();
        assertEquals(GroovyDiagnosticsProvider.UNRESOLVED_CLASS, diagnostic.code());
        assertEquals(DiagnosticOrigin.SEMANTIC, diagnostic.origin());
        assertTrue(diagnostic.message().contains("Missing"), diagnostic.message());
        assertEquals(text.indexOf("Missing"), diagnostic.location().range().start);
        assertEquals("Missing", text.substring(diagnostic.location().range().start, diagnostic.location().range().end));
    }

    @Test
    public void resolvesJdkGroovyAndDefaultImports() {
        String text = """
            import java.time.LocalDate
            import groovy.transform.ToString

            @ToString
            class Example {
                LocalDate date
                List<String> names
                BigDecimal amount
                Closure callback
            }
            """;

        assertEquals(List.of(), diagnose(text, Map.of()));
    }

    @Test
    public void doesNotReportDynamicCode() {
        String text = "def result = something.call(1).property\nresult.whatever()\n";

        assertEquals(List.of(), diagnose(text, Map.of()));
    }

    @Test
    public void reportsSyntaxErrors() {
        List<TextEditorDiagnostic> diagnostics = diagnose("class Example {\n    void broken( {\n}\n", Map.of());

        assertFalse(diagnostics.isEmpty());
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.origin() == DiagnosticOrigin.SYNTAX
            && diagnostic.code().equals(GroovyDiagnosticsProvider.SYNTAX_ERROR)), diagnostics.toString());
    }

    @Test
    public void resolvesClassesDeclaredByOtherProjectSources() {
        String text = "package demo\n\nclass Example {\n    Helper helper\n    Script script\n}\n";
        TextDocumentSnapshot helper = document(directory.resolve("src/main/java/demo/Helper.java"), "java",
            "/* licence */\npackage demo;\n\npublic class Helper {}\n");
        TextDocumentSnapshot script = document(directory.resolve("src/main/groovy/demo/Script.groovy"), "groovy",
            "package demo\nprintln 'hi'\n");

        assertEquals(List.of(), diagnose(text, Map.of(), helper, script));
        assertEquals(List.of("Helper"), unresolvedNames(diagnose(text, Map.of(), script)));
    }

    @Test
    public void resolvesOnlyFromTheConfiguredClasspath() throws Exception {
        String text = "import com.google.common.collect.ImmutableList\n\nclass Example { ImmutableList values }\n";
        String guava = Path.of(ImmutableList.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();

        assertEquals(List.of(), diagnose(text, Map.of("module.:.classpath", guava)));
        assertFalse(unresolvedNames(diagnose(text, Map.of())).isEmpty(),
            "Libraries of the IDE itself must not resolve for the project");
    }

    @Test
    public void holdsBackUnresolvedClassesWhileDependenciesAreImporting() {
        String text = "class Example { Missing value }\n";

        assertEquals(List.of(), diagnose(text, Map.of("project.build.build.gradle", "plugins { id 'groovy' }")));
        assertEquals(1, diagnose(text, Map.of("project.build.build.gradle", "plugins { id 'groovy' }",
            "module.:.classpath", "")).size());
    }

    @Test
    @Timeout(30)
    public void doesNotRunGrabOrOtherGlobalTransformations() {
        String text = "@Grab('com.example.nonexistent:artifact:1.0')\nimport com.example.Thing\n\nclass Example {}\n";

        assertEquals(List.of("com.example.Thing"), unresolvedNames(diagnose(text, Map.of())));
    }

    private List<TextEditorDiagnostic> diagnose(
        String text,
        Map<String, String> settings,
        DocumentSnapshot... others
    ) {
        TextDocumentSnapshot document = document(directory.resolve("src/main/groovy/demo/Example.groovy"), "groovy",
            text);
        Map<DocumentId, DocumentSnapshot> documents = new LinkedHashMap<>();
        documents.put(document.id(), document);
        for (DocumentSnapshot other : others) {
            documents.put(other.id(), other);
        }

        var configuration = new WorkspaceConfigurationSnapshot(1, Set.of(":"), settings);
        var workspace = new WorkspaceSnapshot("groovy-test", documents, configuration, 1);
        return provider.compute(new FeatureRequestContext(workspace, document, CancellationToken.none(),
            Deadline.none())).value();
    }

    private static List<String> unresolvedNames(List<TextEditorDiagnostic> diagnostics) {
        return diagnostics.stream()
            .filter(diagnostic -> diagnostic.code().equals(GroovyDiagnosticsProvider.UNRESOLVED_CLASS))
            .map(diagnostic -> diagnostic.message().substring("unable to resolve class ".length()).strip())
            .toList();
    }

    private static TextDocumentSnapshot document(Path path, String languageId, String text) {
        DocumentUri uri = DocumentUri.fromPath(path);
        return new TextDocumentSnapshot(DocumentId.create(), uri, DocumentVersion.initial(), languageId,
            DocumentOrigin.original(uri), text, StandardCharsets.UTF_8);
    }
}
