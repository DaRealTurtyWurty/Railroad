package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.sst.java.api.JavaSymbolKind;
import dev.railroadide.railroad.ide.sst.project.JavaProjectSemanticIndex;
import dev.railroadide.railroad.ide.sst.project.JavaSourceSnapshotIndex;
import dev.railroadide.railroad.ide.sst.project.JavaSymbolIndex;
import dev.railroadide.railroad.ide.sst.request.internal.ActivityCheckpoint;
import dev.railroadide.railroad.ide.sst.semantic.api.SemanticModel;
import dev.railroadide.railroad.ide.sst.semantic.api.Symbol;
import dev.railroadide.railroad.ide.sst.semantic.api.SymbolKind;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves Java type names and declarations used by editor features.
 */
@ApiStatus.Internal
public final class JavaTypeLookup {
    private final Runnable activityCheck;

    public JavaTypeLookup(Runnable activityCheck) {
        this.activityCheck = activityCheck;
    }

    public static boolean isTypeSymbol(SymbolKind symbolKind) {
        return switch (symbolKind) {
            case JavaSymbolKind.CLASS, JavaSymbolKind.INTERFACE, JavaSymbolKind.ENUM, JavaSymbolKind.ANNOTATION,
                JavaSymbolKind.RECORD -> true;
            default -> false;
        };
    }

    /**
     * Finds the source declaration of a type in the current model or, failing that, in its project source file.
     *
     * @param ownerQualifiedName qualified type name
     * @param model current document model
     * @param projectIndex captured project symbols, or null for the runtime library
     * @return the declaration with the model that owns it, or null when the type has no source declaration
     */
    @Nullable
    public SourceTypeDeclaration sourceTypeDeclaration(
        String ownerQualifiedName,
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (projectIndex != null) {
            ownerQualifiedName = projectIndex.canonicalTypeName(ownerQualifiedName);
        }

        SourceTypeDeclaration inCurrentModel = sourceTypeDeclarationInModel(ownerQualifiedName, model);
        if (inCurrentModel != null)
            return inCurrentModel;

        Path sourceFile = sourceFileForType(ownerQualifiedName, projectIndex);
        if (sourceFile == null)
            return null;

        try {
            String source = JavaSourceSnapshotIndex.readSource(projectIndex, sourceFile);
            SemanticModel declarationModel = JavaSemanticAnalyzer
                .analyzeDeclarationsFacts(source, activityCheck);
            return sourceTypeDeclarationInModel(ownerQualifiedName, declarationModel);
        } catch (IOException exception) {
            return null;
        }
    }

    @Nullable
    public SourceTypeDeclaration sourceTypeDeclarationInModel(
        String ownerQualifiedName,
        SemanticModel model
    ) {
        SyntaxNode declarationNode = findTypeDeclarationNodeByQualifiedName(model, ownerQualifiedName);
        if (declarationNode == null)
            return null;

        Symbol symbol = model.declaredSymbol(declarationNode).orElse(null);
        if (symbol == null || !isTypeSymbol(symbol.kind()))
            return null;

        return new SourceTypeDeclaration(model, declarationNode, symbol.kind());
    }

    private @Nullable Path sourceFileForType(
        String ownerQualifiedName,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (projectIndex == null)
            return null;

        for (JavaProjectSemanticIndex.SymbolDescriptor symbol : projectIndex.lookupQualifiedName(ownerQualifiedName)) {
            ActivityCheckpoint.poll(activityCheck);
            if (isTypeSymbol(symbol.kind()))
                return symbol.sourceFile();
        }

        return null;
    }

    private @Nullable SyntaxNode findTypeDeclarationNodeByQualifiedName(
        SemanticModel model,
        String ownerQualifiedName
    ) {
        Deque<SyntaxNode> stack = new ArrayDeque<>();
        stack.push(model.syntaxTree().root());
        while (!stack.isEmpty()) {
            ActivityCheckpoint.poll(activityCheck);
            SyntaxNode node = stack.pop();
            Symbol symbol = model.declaredSymbol(node).orElse(null);
            if (symbol != null
                && isTypeSymbol(symbol.kind())
                && ownerQualifiedName.equals(symbol.qualifiedName().orElse(null)))
                return node;

            List<SyntaxNode> children = node.children();
            for (int index = children.size() - 1; index >= 0; index--) {
                ActivityCheckpoint.poll(activityCheck);
                stack.push(children.get(index));
            }
        }

        return null;
    }

    @Nullable
    public String resolveQualifiedTypeName(
        @Nullable String text,
        SyntaxNode usageSite,
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (text == null || text.isBlank())
            return null;

        while (text.endsWith("[]")) {
            ActivityCheckpoint.poll(activityCheck);
            text = text.substring(0, text.length() - 2);
        }

        if (Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double", "void").contains(text))
            return text;

        int separator = text.indexOf('.');
        if (separator > 0) {
            if (typeExists(text, projectIndex) || sourceTypeDeclarationInModel(text, model) != null)
                return text;

            String prefix = resolveQualifiedTypeName(text.substring(0, separator), usageSite, model, projectIndex);
            return prefix == null ? text : prefix + text.substring(separator);
        }

        String simpleName = JavaSemanticAnalyzer.simpleTypeName(text);

        Symbol localType = enclosingOrRootTypeBySimpleName(model, simpleName);
        if (localType != null)
            return localType.qualifiedName().orElse(simpleName);

        SyntaxNode root = model.syntaxTree().root();
        for (SyntaxNode child : root.children()) {
            ActivityCheckpoint.poll(activityCheck);
            if (!JavaSyntaxKinds.IMPORT_DECLARATION.id().equals(child.kind().id()))
                continue;

            if (JavaSemanticAnalyzer.hasTokenKind(child, JavaTokenType.STATIC_KEYWORD))
                continue;

            SyntaxNode importTarget = JavaSemanticAnalyzer.directChild(child, JavaSyntaxKinds.IMPORT_TARGET.id());
            String qualified = importTarget == null ? null : JavaSemanticAnalyzer.canonicalQualifiedName(importTarget);
            if (qualified == null || qualified.isBlank())
                continue;

            if (qualified.endsWith(".*")) {
                String wildcardCandidate = qualified.substring(0, qualified.length() - 2) + "." + simpleName;
                if (typeExists(wildcardCandidate, projectIndex))
                    return wildcardCandidate;
            } else if (simpleName.equals(JavaSemanticAnalyzer.lastSegment(qualified)))
                return qualified;

        }

        String currentPackage = currentPackageName(root);
        if (!currentPackage.isBlank()) {
            String inPackage = currentPackage + "." + simpleName;
            if (typeExists(inPackage, projectIndex))
                return inPackage;
        }

        String javaLang = "java.lang." + simpleName;
        if (typeExists(javaLang, projectIndex))
            return javaLang;

        if (typeExists(text, projectIndex))
            return text;

        return null;
    }

    private @Nullable Symbol enclosingOrRootTypeBySimpleName(SemanticModel model, String simpleName) {
        Deque<SyntaxNode> stack = new ArrayDeque<>();
        stack.push(model.syntaxTree().root());
        while (!stack.isEmpty()) {
            ActivityCheckpoint.poll(activityCheck);
            SyntaxNode node = stack.pop();
            Symbol symbol = model.declaredSymbol(node).orElse(null);
            if (symbol != null && isTypeSymbol(symbol.kind()) && simpleName.equals(symbol.simpleName()))
                return symbol;

            List<SyntaxNode> children = node.children();
            for (int index = children.size() - 1; index >= 0; index--) {
                ActivityCheckpoint.poll(activityCheck);
                stack.push(children.get(index));
            }
        }

        return null;
    }

    private boolean typeExists(String qualifiedName, @Nullable JavaSymbolIndex projectIndex) {
        if (qualifiedName == null || qualifiedName.isBlank())
            return false;

        if (projectIndex != null)
            return !projectIndex.lookupQualifiedName(qualifiedName).isEmpty();

        return JavaSemanticAnalyzer.loadJdkQualifiedTypeNames(activityCheck).contains(qualifiedName);
    }

    @Nullable
    public ClassStub lookupBinaryClassStub(
        String qualifiedName,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (qualifiedName == null || qualifiedName.isBlank())
            return null;

        if (projectIndex != null)
            return projectIndex.lookupClassStub(qualifiedName);

        return JavaSemanticAnalyzer.loadJdkClassStubsByQualifiedName(activityCheck).get(qualifiedName);
    }

    @Nullable
    public SyntaxNode findDeepestNodeContaining(SyntaxNode node, int offset) {
        if (offset < node.start() || offset >= node.end())
            return null;

        for (SyntaxNode child : node.children()) {
            ActivityCheckpoint.poll(activityCheck);
            SyntaxNode match = findDeepestNodeContaining(child, offset);
            if (match != null)
                return match;
        }

        return node;
    }

    public String currentPackageName(SyntaxNode root) {
        for (SyntaxNode child : root.children()) {
            ActivityCheckpoint.poll(activityCheck);
            if (!JavaSyntaxKinds.PACKAGE_DECLARATION.id().equals(child.kind().id()))
                continue;

            SyntaxNode qualifiedName = JavaSemanticAnalyzer.directChild(child, JavaSyntaxKinds.QUALIFIED_NAME.id());
            String text = qualifiedName == null ? null : JavaSemanticAnalyzer.canonicalQualifiedName(qualifiedName);
            return text == null ? "" : text;
        }

        return "";
    }

    /**
     * A type's source declaration together with the semantic model it belongs to.
     *
     * @param model model owning the declaration node
     * @param node type declaration node
     * @param kind declared type kind
     */
    public record SourceTypeDeclaration(SemanticModel model, SyntaxNode node, SymbolKind kind) {
    }
}
