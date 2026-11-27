package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import dev.railroadide.railroad.ide.sst.project.JavaJdkSymbolIndex;
import dev.railroadide.railroad.ide.sst.project.JavaSymbolIndex;
import dev.railroadide.railroad.ide.sst.request.internal.ActivityCheckpoint;
import dev.railroadide.railroad.ide.sst.semantic.api.SemanticModel;
import dev.railroadide.railroad.ide.sst.semantic.api.Symbol;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxToken;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxTree;
import dev.railroadide.railroad.plugin.spi.inspection.JavaRuleContext;
import dev.railroadide.railroad.utility.NameMatcher;
import org.jspecify.annotations.Nullable;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Completes accessible Java types from the captured workspace and its dependencies.
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

        JavaSymbolIndex index = completionIndex(projectIndex, check);
        String currentPackage = lookup.currentPackageName(root);
        List<JavaImports.ImportDeclaration> imports = JavaImports.imports(root);
        var model = JavaSemanticAnalyzer.analyzeDeclarationsFacts(tree, index, check);
        var resolution = new JavaRuleContext(tree.documentSnapshot().uri(), document, model, index, check);
        Map<String, String> visible = new HashMap<>();
        Map<String, TypeCandidate> local = localTypes(model, resolution);
        List<CompletionItem> items = new ArrayList<>();
        for (List<TypeCandidate> path : candidates(index, local, prefix, null)) {
            TypeCandidate type = path.getLast();
            String qualified = sourceName(path);
            String description = parentName(qualified);
            String name = type.name();
            if (!accessible(path, resolution, usage, currentPackage))
                continue;

            String inScope = visible.computeIfAbsent(name, key -> inScope(key, resolution, usage, local, imports));
            if (type.qualifiedName().equals(inScope) || qualified.equals(inScope)) {
                items.add(item(name, description, name, List.of()));
            } else if (name.equals(inScope) && importable(path, currentPackage)) {
                var edit = JavaImports.insertType(document, root, imports, qualified);
                items.add(item(name, description, name,
                    List.of(new CompletionItem.Insertion(edit.range().start, edit.text()))));
            } else {
                items.add(item(name, description, qualified, List.of()));
            }
        }

        if (items.isEmpty())
            return null;

        items.sort(Comparator.comparing(CompletionItem::insertText)
            .thenComparing(item -> !item.insertText().equals(item.template().text()))
            .thenComparing(CompletionItem::detail));
        return new CompletionResult(start, items);
    }

    /**
     * Completes a member type following a qualifier in a type reference.
     *
     * @param tree parsed Java source
     * @param dot the qualifier's dot
     * @param projectIndex captured symbols, or {@code null} to use the running JDK
     * @param check cooperative activity check
     * @return matching nested types, or {@code null} when the dot is outside a resolvable type reference
     */
    public static @Nullable CompletionResult completeQualifiedType(
        SyntaxTree tree,
        int dot,
        @Nullable JavaSymbolIndex projectIndex,
        Runnable check
    ) {
        var completion = new JavaTypeCompletion(check);
        SyntaxNode token = completion.lookup.findDeepestNodeContaining(tree.root(), dot);
        SyntaxNode usage = token == null ? null : token.parent().orElse(null);
        if (usage == null || !usage.kind().equals(JavaSyntaxKinds.TYPE_REFERENCE))
            return null;

        var qualifier = new StringBuilder();
        for (SyntaxNode child : usage.children()) {
            ActivityCheckpoint.poll(check);
            if (child.start() >= dot)
                break;

            if (child instanceof SyntaxToken part && !JavaSemanticAnalyzer.isTriviaToken(part)) {
                qualifier.append(JavaUnicodeEscapes.translate(part.text()));
            }
        }

        JavaSymbolIndex index = completionIndex(projectIndex, check);
        SemanticModel model = JavaSemanticAnalyzer.analyzeDeclarationsFacts(tree, index, check);
        List<CompletionItem> items = completion.memberTypes(model, usage, qualifier.toString(), index);
        return items.isEmpty() ? null : new CompletionResult(dot + 1, items);
    }

    /**
     * Lists member types available after a resolved type qualifier.
     *
     * @param model semantic facts for the current source
     * @param usage the qualified access
     * @param qualifier the qualifying type name
     * @param projectIndex captured symbols, or {@code null} to use the running JDK
     * @param check cooperative activity check
     * @return accessible member type suggestions
     */
    public static List<CompletionItem> memberTypes(
        SemanticModel model,
        SyntaxNode usage,
        String qualifier,
        @Nullable JavaSymbolIndex projectIndex,
        Runnable check
    ) {
        return new JavaTypeCompletion(check).memberTypes(model, usage, qualifier, completionIndex(projectIndex, check));
    }

    private List<CompletionItem> memberTypes(
        SemanticModel model,
        SyntaxNode usage,
        String qualifier,
        JavaSymbolIndex index
    ) {
        SyntaxTree tree = model.syntaxTree();
        String document = tree.documentSnapshot().text();
        var rules = new JavaRuleContext(tree.documentUri(), document, model, index, check);
        String owner = rules.resolveQualifiedTypeName(qualifier, usage);
        if (owner == null)
            return List.of();

        String currentPackage = lookup.currentPackageName(tree.root());
        return candidates(index, localTypes(model, rules), "", owner).stream()
            .filter(path -> accessible(path, rules, usage, currentPackage))
            .map(path -> CompletionItem.type(path.getLast().name(), false))
            .sorted(Comparator.comparing(CompletionItem::insertText))
            .toList();
    }

    private static boolean acceptsType(SyntaxNode usage, SyntaxNode token) {
        if (usage.kind().equals(JavaSyntaxKinds.NAME_EXPRESSION))
            return !JavaSemanticAnalyzer.isSelectorNameExpression(usage);

        return usage.kind().equals(JavaSyntaxKinds.TYPE_REFERENCE)
            && usage.children().stream().filter(child -> child instanceof SyntaxToken syntaxToken
                && !JavaSemanticAnalyzer.isTriviaToken(syntaxToken)).findFirst().orElse(null) == token;
    }

    private static JavaSymbolIndex completionIndex(@Nullable JavaSymbolIndex index, Runnable check) {
        return index == null ? JavaJdkSymbolIndex.fromCurrentRuntime(check) : index;
    }

    private Map<String, TypeCandidate> localTypes(SemanticModel model, JavaRuleContext rules) {
        Map<String, TypeCandidate> types = new LinkedHashMap<>();
        collectLocalTypes(model.syntaxTree().root(), model, rules, null, types);
        return types;
    }

    private void collectLocalTypes(
        SyntaxNode node,
        SemanticModel model,
        JavaRuleContext rules,
        @Nullable String owner,
        Map<String, TypeCandidate> types
    ) {
        ActivityCheckpoint.poll(check);
        if (node.kind().equals(JavaSyntaxKinds.BLOCK) || node.kind().equals(JavaSyntaxKinds.ANONYMOUS_CLASS_BODY))
            return;

        Symbol symbol = model.declaredSymbol(node).orElse(null);
        if (symbol != null && JavaTypeLookup.isTypeSymbol(symbol.kind())) {
            String qualified = symbol.qualifiedName().orElse(symbol.simpleName());
            types.put(qualified,
                new TypeCandidate(qualified, symbol.simpleName(), owner, rules.symbolModifiers(symbol)));
            owner = qualified;
        }

        for (SyntaxNode child : node.children()) {
            collectLocalTypes(child, model, rules, owner, types);
        }
    }

    private List<List<TypeCandidate>> candidates(
        JavaSymbolIndex index,
        Map<String, TypeCandidate> local,
        String prefix,
        @Nullable String owner
    ) {
        Set<String> names = new LinkedHashSet<>(local.keySet());
        names.addAll(index.declaredQualifiedNames());
        Map<String, List<TypeCandidate>> matches = new LinkedHashMap<>();
        for (String qualified : names) {
            ActivityCheckpoint.poll(check);
            int lastSeparator = Math.max(qualified.lastIndexOf('.'), qualified.lastIndexOf('$'));
            if (!NameMatcher.matches(prefix, qualified, lastSeparator + 1)
                || owner != null && !qualified.startsWith(owner + ".") && !qualified.startsWith(owner + "$"))
                continue;

            TypeCandidate type = typeCandidate(qualified, index, local);
            if (type == null || !NameMatcher.matches(prefix, type.name())
                || owner != null && !owner.equals(type.owner()))
                continue;

            Deque<TypeCandidate> path = new ArrayDeque<>();
            for (TypeCandidate current = type; current != null;) {
                path.addFirst(current);
                String outer = current.owner();
                if (outer == null)
                    break;

                current = typeCandidate(outer, index, local);
            }

            if (path.getFirst().owner() == null) {
                List<TypeCandidate> candidates = List.copyOf(path);
                matches.putIfAbsent(sourceName(candidates), candidates);
            }
        }

        return List.copyOf(matches.values());
    }

    private @Nullable TypeCandidate typeCandidate(
        String qualified,
        JavaSymbolIndex index,
        Map<String, TypeCandidate> local
    ) {
        TypeCandidate current = local.get(qualified);
        if (current != null)
            return current;

        ClassStub stub = index.lookupClassStub(qualified);
        if (stub != null) {
            String owner = stub.enclosingClassName();
            String binaryName = stub.getFullName();
            String name = owner != null && binaryName.startsWith(owner + "$")
                ? binaryName.substring(owner.length() + 1)
                : JavaSemanticAnalyzer.lastSegment(qualified);
            return validTypeName(name) && (owner == null || binaryName.startsWith(owner + "$"))
                ? new TypeCandidate(binaryName, name, owner, stub.modifiers())
                : null;
        }

        return index.lookupQualifiedName(qualified).stream()
            .filter(symbol -> JavaTypeLookup.isTypeSymbol(symbol.kind()) && validTypeName(symbol.simpleName()))
            .filter(symbol -> symbol.isTopLevel() || qualified.endsWith("." + symbol.simpleName()))
            .map(symbol -> new TypeCandidate(qualified, symbol.simpleName(),
                symbol.isTopLevel() ? null : parentName(qualified),
                symbol.descriptor() == null ? 0 : switch (symbol.descriptor().metadata().visibility()) {
                    case "public" -> Modifier.PUBLIC;
                    case "protected" -> Modifier.PROTECTED;
                    case "private" -> Modifier.PRIVATE;
                    case null, default -> 0;
                }))
            .findFirst().orElse(null);
    }

    private static boolean validTypeName(String name) {
        return SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
    }

    private static String inScope(
        String name,
        JavaRuleContext rules,
        SyntaxNode usage,
        Map<String, TypeCandidate> local,
        List<JavaImports.ImportDeclaration> imports
    ) {
        String resolved = rules.resolveQualifiedTypeName(name, usage);
        TypeCandidate candidate = local.get(resolved);
        if (candidate != null && candidate.owner() != null) {
            String lexical = JavaMemberTypeScope.resolve(name, rules.enclosingTypeSymbol(usage).orElse(null),
                type -> type.declaration().flatMap(rules::enclosingTypeSymbol).orElse(null),
                qualified -> local.containsKey(qualified) ? qualified : null, rules::directSuperTypeNames);
            boolean imported = imports.stream().anyMatch(entry -> entry.isWildcard()
                ? entry.owner().equals(candidate.owner())
                : entry.target().equals(resolved));
            if (resolved.equals(lexical) || imported)
                return resolved;
        } else if (resolved != null && !name.equals(resolved))
            return resolved;

        return imports.stream().filter(entry -> !entry.isWildcard() && !entry.isStatic())
            .filter(entry -> entry.importedName().equals(name)).map(JavaImports.ImportDeclaration::target)
            .findFirst().orElse(name);
    }

    private static boolean accessible(
        List<TypeCandidate> path,
        JavaRuleContext rules,
        SyntaxNode usage,
        String currentPackage
    ) {
        String declaringPackage = parentName(path.getFirst().qualifiedName());
        if (JavaImports.hiddenJdkPackage(declaringPackage) || declaringPackage.isEmpty() && !currentPackage.isEmpty())
            return false;

        String topLevel = rules.topLevelEnclosingTypeSymbol(usage).flatMap(Symbol::qualifiedName).orElse(null);
        for (TypeCandidate type : path) {
            if (Modifier.isPublic(type.modifiers()))
                continue;

            if (Modifier.isPrivate(type.modifiers())) {
                if (!path.getFirst().qualifiedName().equals(topLevel))
                    return false;
            } else if (!declaringPackage.equals(currentPackage)) {
                String owner = type.owner();
                if (!Modifier.isProtected(type.modifiers()) || owner == null
                    || rules.enclosingTypeSymbol(usage).flatMap(Symbol::qualifiedName)
                        .filter(enclosing -> rules.isSubtype(enclosing, owner)).isEmpty())
                    return false;
            }
        }

        return true;
    }

    private static boolean importable(List<TypeCandidate> path, String currentPackage) {
        String declaringPackage = parentName(path.getFirst().qualifiedName());
        return !declaringPackage.isEmpty() && path.stream().allMatch(type -> Modifier.isPublic(type.modifiers())
            || declaringPackage.equals(currentPackage) && !Modifier.isPrivate(type.modifiers()));
    }

    private static String sourceName(List<TypeCandidate> path) {
        var name = new StringBuilder(path.getFirst().qualifiedName());
        for (int index = 1; index < path.size(); index++) {
            name.append('.').append(path.get(index).name());
        }

        return name.toString();
    }

    private static String parentName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    private record TypeCandidate(String qualifiedName, String name, @Nullable String owner, int modifiers) {
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
