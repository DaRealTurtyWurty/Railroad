package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import dev.railroadide.railroad.ide.sst.project.JavaJdkSymbolIndex;
import dev.railroadide.railroad.ide.sst.project.JavaSymbolIndex;
import dev.railroadide.railroad.ide.sst.request.internal.ActivityCheckpoint;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxToken;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxTree;
import dev.railroadide.railroad.plugin.spi.inspection.JavaRuleContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Completes accessible top-level Java types from the captured workspace and its dependencies.
 */
public final class JavaTypeCompletion {
    private final Runnable check;
    private final JavaTypeLookup lookup;

    private JavaTypeCompletion(Runnable check) {
        this.check = check;
        this.lookup = new JavaTypeLookup(check);
    }

    /**
     * Computes type suggestions for a typed identifier prefix.
     *
     * @param tree parsed Java source
     * @param triggerAt offset of a typed character in the prefix
     * @param projectIndex captured symbols, or {@code null} to use the running JDK
     * @param check cooperative activity check
     * @return type suggestions, or {@code null} when there are none
     */
    public static @Nullable CompletionResult complete(
        SyntaxTree tree,
        int triggerAt,
        @Nullable JavaSymbolIndex projectIndex,
        Runnable check
    ) {
        if (triggerAt < 0 || triggerAt >= tree.documentSnapshot().text().length())
            return null;

        return new JavaTypeCompletion(check).complete(tree, triggerAt, projectIndex);
    }

    @Nullable
    private CompletionResult complete(SyntaxTree tree, int triggerAt, @Nullable JavaSymbolIndex projectIndex) {
        String document = tree.documentSnapshot().text();
        int start = triggerAt;
        while (start >= 0 && Character.isJavaIdentifierPart(document.charAt(start))) {
            start--;
        }

        start++;
        if (triggerAt <= start || !Character.isJavaIdentifierStart(document.charAt(start)))
            return null;

        String prefix = document.substring(start, triggerAt + 1);
        SyntaxNode root = tree.root();
        SyntaxNode token = lookup.findDeepestNodeContaining(root, start);
        SyntaxNode usage = token == null ? null : token.parent().orElse(null);
        if (!(token instanceof SyntaxToken) || token.start() != start || usage == null || !acceptsType(usage, token))
            return null;

        JavaSymbolIndex index = projectIndex == null ? JavaJdkSymbolIndex.fromCurrentRuntime(check) : projectIndex;
        String currentPackage = lookup.currentPackageName(root);
        List<JavaImports.ImportDeclaration> imports = JavaImports.imports(root);
        var model = JavaSemanticAnalyzer.analyzeDeclarationsFacts(tree, index, check);
        var resolution = new JavaRuleContext(tree.documentSnapshot().uri(), document, model, index, check);
        Map<String, String> visible = new HashMap<>();
        for (JavaImports.ImportDeclaration entry : imports) {
            if (!entry.isWildcard()) {
                visible.putIfAbsent(entry.importedName(), entry.target());
            }
        }

        List<CompletionItem> items = new ArrayList<>();
        for (String qualified : index.declaredQualifiedNames()) {
            ActivityCheckpoint.poll(check);
            int separator = qualified.lastIndexOf('.') + 1;
            if (!qualified.startsWith(prefix, separator) || qualified.indexOf('$') >= 0)
                continue;

            String name = qualified.substring(separator);
            String owningPackage = separator == 0 ? "" : qualified.substring(0, separator - 1);
            if (JavaImports.hiddenJdkPackage(owningPackage)
                || !accessible(index, qualified, owningPackage, currentPackage))
                continue;

            String inScope = visible.computeIfAbsent(name, key -> resolution.resolveQualifiedTypeName(key, usage));
            if (qualified.equals(inScope)) {
                items.add(item(name, owningPackage, name, List.of()));
            } else if (name.equals(inScope)) {
                var edit = JavaImports.insertType(document, root, imports, qualified);
                items.add(item(name, owningPackage, name,
                    List.of(new CompletionItem.Insertion(edit.range().start, edit.text()))));
            } else {
                items.add(item(name, owningPackage, qualified, List.of()));
            }
        }

        if (items.isEmpty())
            return null;

        items.sort(Comparator.comparing(CompletionItem::insertText)
            .thenComparing(item -> !item.insertText().equals(item.template().text()))
            .thenComparing(CompletionItem::detail));
        return new CompletionResult(start, items);
    }

    private static boolean acceptsType(SyntaxNode usage, SyntaxNode token) {
        if (usage.kind().equals(JavaSyntaxKinds.NAME_EXPRESSION))
            return !JavaSemanticAnalyzer.isSelectorNameExpression(usage);

        return usage.kind().equals(JavaSyntaxKinds.TYPE_REFERENCE)
            && usage.children().stream().filter(child -> child instanceof SyntaxToken syntaxToken
                && !JavaSemanticAnalyzer.isTriviaToken(syntaxToken)).findFirst().orElse(null) == token;
    }

    private static boolean accessible(JavaSymbolIndex index, String qualified, String owning, String current) {
        if (owning.isEmpty() && !current.isEmpty())
            return false;

        ClassStub stub = index.lookupClassStub(qualified);
        if (stub != null)
            return stub.enclosingClassName() == null && (owning.equals(current) || Modifier.isPublic(stub.modifiers()));

        return index.lookupQualifiedName(qualified).stream()
            .anyMatch(symbol -> symbol.isTopLevel() && JavaTypeLookup.isTypeSymbol(symbol.kind())
                && (owning.equals(current) || symbol.descriptor() != null
                    && "public".equals(symbol.descriptor().metadata().visibility())));
    }

    private static CompletionItem item(
        String name,
        String owningPackage,
        String text,
        List<CompletionItem.Insertion> imports
    ) {
        return new CompletionItem(name, name, "", owningPackage, CompletionItem.Kind.TYPE, false,
            new CompletionItem.Template(text, text.length(), imports));
    }
}
