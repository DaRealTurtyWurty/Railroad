package dev.railroadide.railroad.ide.language.impl.groovy;

import dev.railroadide.railroad.ide.classparser.ClassStubParser;
import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.classparser.stub.MethodStub;
import dev.railroadide.railroad.ide.diagnostics.EditorDiagnostic.TextEditorDiagnostic;
import dev.railroadide.railroad.ide.diagnostics.JavaDiagnosticsProvider;
import dev.railroadide.railroad.ide.language.impl.index.JavaWorkspaceAnalysisProvider;
import dev.railroadide.railroad.ide.language.jvm.JvmClassDeclaration;
import dev.railroadide.railroad.ide.language.jvm.JvmDeclarationProvider;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.document.api.DocumentOrigin;
import dev.railroadide.railroad.ide.sst.document.api.DocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.DocumentUri;
import dev.railroadide.railroad.ide.sst.document.api.DocumentVersion;
import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import dev.railroadide.railroad.ide.sst.impl.java.JavaSstServices;
import dev.railroadide.railroad.ide.sst.project.JavaLibrarySources;
import dev.railroadide.railroad.ide.sst.request.api.CancellationToken;
import dev.railroadide.railroad.ide.sst.request.api.Deadline;
import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import dev.railroadide.railroad.ide.sst.semantic.api.DefinitionTarget;
import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.language.LanguageSupports;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class GroovyJavaInteropTest {
    private static final String GREETER = """
        package demo

        class Greeter {
            String name
            final int id = 1

            Greeter() {}

            Greeter(String name) {
                this.name = name
            }

            String greet(String who, String punctuation = '!') {
                "Hello $who$punctuation"
            }

            static class Inner {}
        }
        """;
    private static final String USES_GROOVY = """
        package demo;

        public class UsesGroovy {
            String run(Greeter greeter) {
                Greeter.Inner inner = new Greeter.Inner();
                greeter.setName("Ada");
                return greeter.greet("you") + greeter.greet("you", "?") + greeter.getName() + greeter.getId();
            }

            Greeter make() {
                return new Greeter("Grace");
            }
        }
        """;

    @TempDir
    public Path directory;
    private final GroovyCompiler compiler = new GroovyCompiler();
    private final GroovyDeclarationProvider groovy = new GroovyDeclarationProvider(compiler);

    @BeforeAll
    public static void initialize() {
        WorkspaceModes.initialize();
        LanguageSupports.initialize();
    }

    @AfterEach
    public void closeCompiler() {
        compiler.close();
    }

    @Test
    public void javaResolvesGroovyClassesMembersAndGeneratedAccessors() {
        TextDocumentSnapshot java = document("src/main/java/demo/UsesGroovy.java", "java", USES_GROOVY);
        TextDocumentSnapshot greeter = document("src/main/groovy/demo/Greeter.groovy", "groovy", GREETER);
        FeatureRequestContext context = context(java, greeter);

        assertEquals(List.of(), resolutionErrors(context, () -> List.of(groovy)));
        assertFalse(resolutionErrors(context, List::of).isEmpty(),
            "Without Groovy declarations Java cannot resolve Greeter");
    }

    @Test
    public void javaNavigatesIntoTheGroovySource() {
        TextDocumentSnapshot java = document("src/main/java/demo/UsesGroovy.java", "java", USES_GROOVY);
        TextDocumentSnapshot greeter = document("src/main/groovy/demo/Greeter.groovy", "groovy", GREETER);
        FeatureRequestContext context = context(java, greeter);
        var provider = new JavaWorkspaceAnalysisProvider();
        try (var services = new JavaSstServices(JavaLibrarySources.shared(),
            request -> provider.capture(request, () -> List.of(groovy)))) {
            List<DefinitionTarget> type = services.definitions(context, USES_GROOVY.indexOf("Greeter greeter"))
                .value();
            assertEquals(1, type.size(), type.toString());
            assertEquals(greeter.uri(), type.getFirst().snapshot().uri());
            assertEquals(GREETER.indexOf("Greeter {"), type.getFirst().location().range().start);

            List<DefinitionTarget> method = services.definitions(context, USES_GROOVY.indexOf("greet(\"you\", ")
                + 1).value();
            assertEquals(1, method.size(), method.toString());
            assertEquals(GREETER.indexOf("greet(String"), method.getFirst().location().range().start);
        } finally {
            provider.close();
        }
    }

    @Test
    public void describesGroovyClassesAsClassFilesWithGeneratedMembers() {
        TextDocumentSnapshot greeter = document("src/main/groovy/demo/Greeter.groovy", "groovy", GREETER);
        List<JvmClassDeclaration> declarations = groovy.declarations(workspace(greeter), Set.of(), () -> {
        });

        ClassStub stub = declarations.stream().map(declaration -> ClassStubParser.parse(
            new ClassReader(declaration.classFile()))).filter(candidate -> candidate.name().equals("Greeter"))
            .findFirst().orElseThrow();
        assertEquals("demo", stub.packageName());
        assertEquals(Set.of(1, 2), methods(stub, "greet"), "Default parameters generate an overload");
        assertEquals(Set.of(0), methods(stub, "getName"));
        assertEquals(Set.of(1), methods(stub, "setName"));
        assertEquals(Set.of(0), methods(stub, "getId"));
        assertEquals(Set.of(), methods(stub, "setId"), "Final properties have no setter");
        assertEquals(2, stub.constructors().size());
        assertTrue(declarations.stream().anyMatch(declaration -> ClassStubParser.parse(
            new ClassReader(declaration.classFile())).name().equals("Greeter$Inner")));
    }

    @Test
    public void reusesDeclarationsWhileGroovySourcesAreUnchanged() {
        TextDocumentSnapshot greeter = document("src/main/groovy/demo/Greeter.groovy", "groovy", GREETER);
        TextDocumentSnapshot java = document("src/main/java/demo/UsesGroovy.java", "java", USES_GROOVY);
        WorkspaceSnapshot first = workspace(greeter, java);
        List<JvmClassDeclaration> declared = groovy.declarations(first, Set.of(), () -> {
        });

        TextDocumentSnapshot editedJava = document("src/main/java/demo/UsesGroovy.java", "java", USES_GROOVY + " ");
        assertSame(declared, groovy.declarations(workspace(greeter, editedJava), Set.of(), () -> {
        }));
    }

    @Test
    public void keepsTheLastParsedClassesWhileASourceHasSyntaxErrors() {
        DocumentId id = DocumentId.create();
        TextDocumentSnapshot valid = document(id, "src/main/groovy/demo/Greeter.groovy", GREETER, 1);
        TextDocumentSnapshot broken = document(id, "src/main/groovy/demo/Greeter.groovy", GREETER + "class {", 2);

        assertFalse(groovy.declarations(workspace(valid), Set.of(), () -> {
        }).isEmpty());
        assertFalse(groovy.declarations(workspace(broken), Set.of(), () -> {
        }).isEmpty());
    }

    @Test
    public void limitsDeclarationsToTheVisibleSourceRoots() {
        TextDocumentSnapshot greeter = document("src/test/groovy/demo/Greeter.groovy", "groovy", GREETER);
        Path mainRoot = directory.resolve("src/main/groovy").toAbsolutePath().normalize();

        assertEquals(List.of(), groovy.declarations(workspace(greeter), Set.of(mainRoot), () -> {
        }));
    }

    private List<String> resolutionErrors(
        FeatureRequestContext context,
        Supplier<List<JvmDeclarationProvider>> foreign
    ) {
        var provider = new JavaWorkspaceAnalysisProvider();
        try {
            var diagnostics = new JavaDiagnosticsProvider(null, null, request -> provider.capture(request, foreign));
            return diagnostics.compute(context).value().stream().map(TextEditorDiagnostic::code)
                .filter(code -> code != null && code.startsWith("SEM_UNRESOLVED")).toList();
        } finally {
            provider.close();
        }
    }

    private static Set<Integer> methods(ClassStub stub, String name) {
        return stub.methods().stream().filter(method -> method.name().equals(name))
            .map(MethodStub::parameters).map(List::size).collect(Collectors.toSet());
    }

    private FeatureRequestContext context(TextDocumentSnapshot request, DocumentSnapshot... others) {
        List<DocumentSnapshot> documents = new ArrayList<>(List.of(others));
        documents.addFirst(request);
        return new FeatureRequestContext(workspace(documents.toArray(DocumentSnapshot[]::new)), request,
            CancellationToken.none(), Deadline.none());
    }

    private static WorkspaceSnapshot workspace(DocumentSnapshot... documents) {
        Map<DocumentId, DocumentSnapshot> byId = new LinkedHashMap<>();
        for (DocumentSnapshot document : documents) {
            byId.put(document.id(), document);
        }

        return new WorkspaceSnapshot("interop", byId, new WorkspaceConfigurationSnapshot(1, Set.of(":"), Map.of()),
            1);
    }

    private TextDocumentSnapshot document(String path, String languageId, String text) {
        DocumentUri uri = DocumentUri.fromPath(directory.resolve(path));
        return new TextDocumentSnapshot(DocumentId.create(), uri, DocumentVersion.initial(), languageId,
            DocumentOrigin.original(uri), text, StandardCharsets.UTF_8);
    }

    private TextDocumentSnapshot document(DocumentId id, String path, String text, long version) {
        DocumentUri uri = DocumentUri.fromPath(directory.resolve(path));
        return new TextDocumentSnapshot(id, uri, new DocumentVersion(version), "groovy", DocumentOrigin.original(uri),
            text, StandardCharsets.UTF_8);
    }
}
