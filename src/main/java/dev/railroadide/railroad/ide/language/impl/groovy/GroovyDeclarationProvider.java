package dev.railroadide.railroad.ide.language.impl.groovy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.ide.language.impl.GroovyLanguageSupport;
import dev.railroadide.railroad.ide.language.jvm.JvmClassDeclaration;
import dev.railroadide.railroad.ide.language.jvm.JvmDeclarationProvider;
import dev.railroadide.railroad.ide.language.jvm.JvmSourceSets;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.document.api.DocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.ModuleNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Describes the classes of a workspace's Groovy documents to other JVM languages. Each document is compiled with the
 * session's {@link GroovyCompiler}; a document that fails to parse keeps the classes of its last revision that did.
 */
public final class GroovyDeclarationProvider implements JvmDeclarationProvider {
    private final GroovyCompiler compiler;
    private final Cache<Key, List<JvmClassDeclaration>> results = CacheBuilder.newBuilder().maximumSize(8).build();
    private final Cache<DocumentId, List<JvmClassDeclaration>> lastParsed = CacheBuilder.newBuilder()
        .maximumSize(10_000).build();

    private record Key(
        List<TextDocumentSnapshot> documents,
        Set<Path> sourceRoots,
        WorkspaceConfigurationSnapshot configuration
    ) {
    }

    /**
     * Creates declarations sharing a session's compiler.
     *
     * @param compiler session compiler
     */
    public GroovyDeclarationProvider(GroovyCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public List<JvmClassDeclaration> declarations(
        WorkspaceSnapshot workspace,
        Set<Path> sourceRoots,
        Runnable activityCheck
    ) {
        List<TextDocumentSnapshot> documents = new ArrayList<>();
        for (DocumentSnapshot document : workspace.documents().values()) {
            activityCheck.run();
            if (document instanceof TextDocumentSnapshot text
                && GroovyLanguageSupport.LANGUAGE_ID.equals(text.languageId()) && visible(text, sourceRoots)) {
                documents.add(text);
            }
        }

        documents.sort(Comparator.comparing(document -> document.uri().toString()));
        var key = new Key(List.copyOf(documents), Set.copyOf(sourceRoots), workspace.configuration());
        List<JvmClassDeclaration> cached = results.getIfPresent(key);
        if (cached != null)
            return cached;

        Set<String> projectTypes = compiler.projectTypes(workspace, activityCheck);
        List<JvmClassDeclaration> declarations = new ArrayList<>();
        for (TextDocumentSnapshot document : documents) {
            activityCheck.run();
            var inputs = JvmSourceSets.compileInputs(workspace.configuration(), document.uri().filePath().orElse(null));
            ModuleNode module = compiler.compile(document, inputs.classpath(), projectTypes, activityCheck).module();
            if (module == null) {
                declarations.addAll(lastParsed.asMap().getOrDefault(document.id(), List.of()));
                continue;
            }

            List<JvmClassDeclaration> described = describe(module, document);
            lastParsed.put(document.id(), described);
            declarations.addAll(described);
        }

        List<JvmClassDeclaration> result = List.copyOf(declarations);
        activityCheck.run();
        results.put(key, result);
        return result;
    }

    private static boolean visible(TextDocumentSnapshot document, Set<Path> sourceRoots) {
        if (sourceRoots.isEmpty())
            return true;

        Path file = document.uri().filePath().map(JvmSourceSets::lookupPath).orElse(null);
        return file != null && sourceRoots.stream().anyMatch(file::startsWith);
    }

    private static List<JvmClassDeclaration> describe(ModuleNode module, TextDocumentSnapshot document) {
        List<JvmClassDeclaration> described = new ArrayList<>();
        for (ClassNode node : module.getClasses()) {
            if (node instanceof InnerClassNode inner && inner.isAnonymous())
                continue;

            try {
                described.add(GroovyClassFiles.describe(node, document));
            } catch (RuntimeException exception) {
                Railroad.LOGGER.debug("Cannot describe Groovy class {} in {}", node.getName(), document.uri(),
                    exception);
            }
        }

        return List.copyOf(described);
    }
}
