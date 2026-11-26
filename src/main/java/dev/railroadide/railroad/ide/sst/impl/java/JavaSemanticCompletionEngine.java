package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.sst.java.api.JavaSymbolKind;
import dev.railroadide.railroad.ide.sst.java.api.JavaTypes;
import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.sst.request.internal.ActivityCheckpoint;
import dev.railroadide.railroad.ide.classparser.stub.FieldStub;
import dev.railroadide.railroad.ide.classparser.stub.MethodStub;
import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import dev.railroadide.railroad.ide.sst.project.JavaProjectSemanticIndex;
import dev.railroadide.railroad.ide.sst.project.JavaSymbolIndex;
import dev.railroadide.railroad.ide.sst.project.JavaSourceSnapshotIndex;
import dev.railroadide.railroad.ide.sst.semantic.api.SemanticModel;
import dev.railroadide.railroad.ide.sst.semantic.api.Symbol;
import dev.railroadide.railroad.ide.sst.semantic.api.SymbolKind;
import dev.railroadide.railroad.ide.sst.semantic.api.Type;
import dev.railroadide.railroad.ide.sst.semantic.api.TypeReference;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxTree;
import org.jetbrains.annotations.Nullable;

import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxToken;
import javax.lang.model.SourceVersion;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.lang.reflect.Modifier;

/**
 * SST-backed completion: dot completion for source/project/JDK members and override completion at
 * member-declaration positions.
 */
public final class JavaSemanticCompletionEngine {
    private final Runnable activityCheck;

    private JavaSemanticCompletionEngine(Runnable activityCheck) {
        this.activityCheck = new ActivityCheckpoint(activityCheck);
    }

    /**
     * Computes accessible source, project, and standard-library members for a dot-completion trigger, or the
     * overridable inherited methods when the trigger is not a dot and lies at a member-declaration position.
     *
     * @param document the Java document text
     * @param triggerAt the zero-based offset of the triggering dot, of the first character of the typed member
     *            name, or of the character before the caret
     * @param projectIndex the project symbol index, or {@code null} to use the standard library
     * @return sorted member completions, or {@code null} when the trigger or receiver yields no candidates
     */
    public static @Nullable CompletionResult compute(
        String document,
        int triggerAt,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        return compute(document, triggerAt, projectIndex, ActivityCheckpoint::checkInterrupted);
    }

    /**
     * Computes completion while checking cancellation throughout semantic and member-graph traversal.
     *
     * @param document captured Java text
     * @param triggerAt dot offset, start of the typed member name, or the character before the caret
     * @param projectIndex captured external symbols, or null for the runtime library
     * @param activityCheck cooperative request activity check
     * @return completed suggestions, or null when unsupported
     */
    public static @Nullable CompletionResult compute(
        String document,
        int triggerAt,
        @Nullable JavaSymbolIndex projectIndex,
        Runnable activityCheck
    ) {
        Objects.requireNonNull(activityCheck, "activityCheck").run();
        CompletionResult result = new JavaSemanticCompletionEngine(activityCheck)
            .computeMembers(document, triggerAt, projectIndex);
        activityCheck.run();
        return result;
    }

    private @Nullable CompletionResult computeMembers(
        String document,
        int triggerAt,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (document == null || document.isEmpty() || triggerAt < 0 || triggerAt >= document.length())
            return null;

        if (document.charAt(triggerAt) != '.')
            return new OverrideCompletion(document, projectIndex).compute(triggerAt);

        SemanticModel model = JavaSemanticAnalyzer.analyzeFacts(document, projectIndex, activityCheck);
        SyntaxTree tree = model.syntaxTree();

        SyntaxNode targetNode = findDeepestNodeContaining(tree.root(), triggerAt);
        if (targetNode == null)
            return null;

        CompletionTarget target = resolveCompletionTarget(targetNode, model, projectIndex);
        if (target == null || target.ownerQualifiedName() == null || target.ownerQualifiedName().isBlank())
            return null;

        String currentPackage = currentPackageName(tree.root());
        Map<String, CompletionItem> itemsByKey = new LinkedHashMap<>();
        collectMembersRecursive(
            model,
            projectIndex,
            target.ownerQualifiedName(),
            target.staticContext(),
            currentPackage,
            itemsByKey,
            new LinkedHashSet<>());

        if (itemsByKey.isEmpty())
            return null;

        List<CompletionItem> items = itemsByKey.values().stream()
            .sorted(Comparator.comparing(CompletionItem::displayText, String.CASE_INSENSITIVE_ORDER))
            .toList();
        return new CompletionResult(triggerAt + 1, items);
    }

    private @Nullable CompletionTarget resolveCompletionTarget(
        SyntaxNode node,
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        for (SyntaxNode current = node; current != null; current = current.parent().orElse(null)) {
            ActivityCheckpoint.poll(activityCheck);
            SyntaxNode receiver = JavaSemanticAnalyzer.explicitReceiver(current);
            if (receiver != null)
                return completionTargetForReceiver(receiver, current, model, projectIndex);
        }

        return null;
    }

    private @Nullable CompletionTarget completionTargetForReceiver(
        SyntaxNode receiver,
        SyntaxNode usageSite,
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (JavaSyntaxKinds.THIS_EXPRESSION.id().equals(receiver.kind().id())) {
            Symbol enclosing = enclosingTypeSymbol(usageSite, model);
            String ownerQualifiedName = enclosing == null ? null : enclosing.qualifiedName().orElse(null);
            return ownerQualifiedName == null ? null : new CompletionTarget(ownerQualifiedName, false);
        }

        if (JavaSyntaxKinds.SUPER_EXPRESSION.id().equals(receiver.kind().id())) {
            Symbol enclosing = enclosingTypeSymbol(usageSite, model);
            String ownerQualifiedName = enclosing == null ? null : enclosing.qualifiedName().orElse(null);
            String superQualifiedName = superTypeQualifiedName(ownerQualifiedName, model, projectIndex);
            return superQualifiedName == null ? null : new CompletionTarget(superQualifiedName, false);
        }

        Symbol resolved = model.resolvedSymbol(receiver).orElse(null);
        if (resolved != null) {
            if (isTypeSymbol(resolved.kind()))
                return completionTarget(resolved.qualifiedName().orElse(null), true);

            if (resolved.kind() == JavaSymbolKind.CONSTRUCTOR)
                return completionTarget(ownerQualifiedName(resolved.qualifiedName().orElse(null)), false);
        }

        Type inferred = model.inferredType(receiver).orElse(null);
        if (inferred != null) {
            String displayName = inferred.displayName();
            if (inferred.kind() == JavaTypes.Kind.ARRAY)
                return new CompletionTarget("java.lang.Object", false);

            if (inferred.kind() == JavaTypes.Kind.DECLARED)
                return completionTarget(resolveQualifiedTypeName(displayName, usageSite, model, projectIndex), false);
        }

        String canonical = JavaSemanticAnalyzer.canonicalQualifiedName(receiver);
        if (canonical != null) {
            String qualified = resolveQualifiedTypeName(canonical, usageSite, model, projectIndex);
            if (qualified != null)
                return new CompletionTarget(qualified, true);
        }

        return null;
    }

    private @Nullable CompletionTarget completionTarget(
        @Nullable String ownerQualifiedName,
        boolean staticContext
    ) {
        if (ownerQualifiedName == null || ownerQualifiedName.isBlank())
            return null;

        return new CompletionTarget(ownerQualifiedName, staticContext);
    }

    private void collectMembersRecursive(
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex,
        String ownerQualifiedName,
        boolean staticContext,
        String currentPackage,
        Map<String, CompletionItem> out,
        Set<String> visitedOwners
    ) {
        if (!visitedOwners.add(ownerQualifiedName))
            return;

        collectLocalMembers(model, ownerQualifiedName, staticContext, out);
        ClassStub binary = lookupBinaryClassStub(ownerQualifiedName, projectIndex);
        if (binary == null) {
            collectProjectMembers(projectIndex, ownerQualifiedName, staticContext, out);
        } else {
            collectBinaryMembers(binary, staticContext, currentPackage, out);
        }

        for (String superType : directSuperTypes(ownerQualifiedName, model, projectIndex)) {
            ActivityCheckpoint.poll(activityCheck);
            collectMembersRecursive(model, projectIndex, superType, staticContext, currentPackage, out, visitedOwners);
        }
    }

    private void collectLocalMembers(
        SemanticModel model,
        String ownerQualifiedName,
        boolean staticContext,
        Map<String, CompletionItem> out
    ) {
        collectLocalMembersRecursive(model.syntaxTree().root(), model, ownerQualifiedName, staticContext, out);
    }

    private void collectLocalMembersRecursive(
        SyntaxNode node,
        SemanticModel model,
        String ownerQualifiedName,
        boolean staticContext,
        Map<String, CompletionItem> out
    ) {
        Symbol declared = model.declaredSymbol(node).orElse(null);
        if (declared != null) {
            String declarationOwnerQualifiedName = ownerQualifiedName(declared.qualifiedName().orElse(null));
            if (ownerQualifiedName.equals(declarationOwnerQualifiedName)) {
                switch (declared.kind()) {
                    case JavaSymbolKind.FIELD -> addFieldItem(
                        out,
                        declared.simpleName(),
                        typeOfFieldSymbol(declared, model).displayName(),
                        isStaticDeclaration(node));
                    case JavaSymbolKind.METHOD -> addMethodItem(
                        out,
                        declared.simpleName(),
                        parameterTypesFromDeclaration(node),
                        returnTypeFromMethodDeclaration(node),
                        isStaticDeclaration(node));
                    case JavaSymbolKind.CLASS, JavaSymbolKind.INTERFACE, JavaSymbolKind.ENUM, JavaSymbolKind.ANNOTATION,
                        JavaSymbolKind.RECORD -> {
                        if (staticContext && isStaticDeclaration(node)) {
                            addTypeItem(out, declared.simpleName(), true);
                        }
                    }

                    default -> {
                    }
                }
            }
        }

        for (SyntaxNode child : node.children()) {
            ActivityCheckpoint.poll(activityCheck);
            collectLocalMembersRecursive(child, model, ownerQualifiedName, staticContext, out);
        }
    }

    private void collectProjectMembers(
        @Nullable JavaSymbolIndex projectIndex,
        String ownerQualifiedName,
        boolean staticContext,
        Map<String, CompletionItem> out
    ) {
        if (projectIndex == null)
            return;

        for (JavaProjectSemanticIndex.SymbolDescriptor symbol : projectIndex.lookupMembers(ownerQualifiedName)) {
            ActivityCheckpoint.poll(activityCheck);
            switch (symbol.kind()) {
                case JavaSymbolKind.FIELD -> {
                    if (symbol.isStatic() == staticContext) {
                        TypeReference type = symbol.descriptor() == null ? null : symbol.descriptor().declaredType();
                        addFieldItem(out, symbol.simpleName(), type == null ? "?" : type.displayName(),
                            symbol.isStatic());
                    }
                }

                case JavaSymbolKind.METHOD -> {
                    if (symbol.isStatic() == staticContext) {
                        addMethodItem(
                            out,
                            symbol.simpleName(),
                            parameterDisplaysFromSignature(symbol),
                            "?",
                            symbol.isStatic());
                    }
                }

                default -> {
                }
            }
        }
    }

    private void collectBinaryMembers(
        ClassStub stub,
        boolean staticContext,
        String currentPackage,
        Map<String, CompletionItem> out
    ) {
        for (FieldStub field : stub.fields()) {
            ActivityCheckpoint.poll(activityCheck);
            boolean isStatic = Modifier.isStatic(field.modifiers());
            if (isStatic != staticContext)
                continue;

            if (!isAccessible(field.modifiers(), stub.packageName(), currentPackage))
                continue;

            addFieldItem(out, field.name(), renderJvmType(field.type()), isStatic);
        }

        for (MethodStub method : stub.methods()) {
            ActivityCheckpoint.poll(activityCheck);
            boolean isStatic = Modifier.isStatic(method.modifiers());
            if (isStatic != staticContext)
                continue;

            if (!isAccessible(method.modifiers(), stub.packageName(), currentPackage))
                continue;

            List<String> parameters = method.parameters().stream()
                .map(parameter -> renderJvmType(parameter.type()))
                .toList();
            addMethodItem(out, method.name(), parameters, renderJvmType(method.returnType()), isStatic);
        }
    }

    private void addFieldItem(Map<String, CompletionItem> out, String name, String type, boolean isStatic) {
        out.putIfAbsent("F:" + name, CompletionItem.field(name, type, isStatic));
    }

    private void addMethodItem(
        Map<String, CompletionItem> out,
        String name,
        List<String> parameterTypes,
        String returnType,
        boolean isStatic
    ) {
        String parameters = String.join(", ", parameterTypes);
        var item = CompletionItem.method(name, parameters, returnType, isStatic);
        out.putIfAbsent("M:" + item.label() + item.suffix(), item);
    }

    private void addTypeItem(Map<String, CompletionItem> out, String name, boolean isStatic) {
        out.putIfAbsent("T:" + name, CompletionItem.type(name, isStatic));
    }

    private Type typeOfFieldSymbol(Symbol symbol, SemanticModel model) {
        SyntaxNode declaration = symbol.declaration().orElse(null);
        if (declaration == null)
            return new JavaTypes.UnknownType("?");

        SyntaxNode typeRef = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.TYPE_REFERENCE.id());
        if (typeRef == null) {
            SyntaxNode parent = declaration.parent().orElse(null);
            typeRef = parent == null
                ? null
                : JavaSemanticAnalyzer.directChild(parent, JavaSyntaxKinds.TYPE_REFERENCE.id());
        }

        return typeRef == null
            ? new JavaTypes.UnknownType("?")
            : model.inferredType(typeRef).orElse(new JavaTypes.UnknownType("?"));
    }

    private String returnTypeFromMethodDeclaration(SyntaxNode declaration) {
        SyntaxNode typeRef = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.TYPE_REFERENCE.id());
        String text = typeRef == null ? null : JavaSemanticAnalyzer.canonicalTypeText(typeRef);
        return text == null || text.isBlank() ? "void" : text;
    }

    private List<String> parameterTypesFromDeclaration(SyntaxNode declaration) {
        SyntaxNode parameterList = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.PARAMETER_LIST.id());
        if (parameterList == null)
            return List.of();

        List<String> result = new ArrayList<>();
        for (SyntaxNode child : parameterList.children()) {
            ActivityCheckpoint.poll(activityCheck);
            if (!JavaSyntaxKinds.PARAMETER.id().equals(child.kind().id()))
                continue;

            SyntaxNode typeRef = JavaSemanticAnalyzer.directChild(child, JavaSyntaxKinds.TYPE_REFERENCE.id());
            String text = typeRef == null ? null : JavaSemanticAnalyzer.canonicalTypeText(typeRef);
            result.add(text == null || text.isBlank() ? "?" : text);
        }

        return List.copyOf(result);
    }

    private List<String> parameterDisplaysFromSignature(JavaProjectSemanticIndex.SymbolDescriptor symbol) {
        if (symbol.structuredSignature() != null)
            return symbol.structuredSignature().parameters().stream()
                .map(parameter -> parameter.type().displayName() + (parameter.varargs() ? "..." : "")).toList();

        String signature = symbol.signature();
        if (signature == null || signature.isBlank() || "()".equals(signature))
            return List.of();

        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open + 1)
            return List.of();

        return Arrays.stream(signature.substring(open + 1, close).split(","))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .toList();
    }

    private boolean isStaticDeclaration(SyntaxNode declaration) {
        if (JavaSemanticAnalyzer.hasTokenKind(declaration, JavaTokenType.STATIC_KEYWORD))
            return true;

        SyntaxNode parent = declaration.parent().orElse(null);
        return parent != null && JavaSemanticAnalyzer.hasTokenKind(parent, JavaTokenType.STATIC_KEYWORD);
    }

    private boolean isAccessible(int modifiers, String declaringPackage, String currentPackage) {
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
            return true;

        if (Modifier.isPrivate(modifiers))
            return false;

        return Objects.equals(declaringPackage, currentPackage);
    }

    private @Nullable Symbol enclosingTypeSymbol(SyntaxNode node, SemanticModel model) {
        for (SyntaxNode current = node; current != null; current = current.parent().orElse(null)) {
            ActivityCheckpoint.poll(activityCheck);
            Symbol declared = model.declaredSymbol(current).orElse(null);
            if (declared != null && isTypeSymbol(declared.kind()))
                return declared;
        }

        return null;
    }

    private boolean isTypeSymbol(SymbolKind symbolKind) {
        return switch (symbolKind) {
            case JavaSymbolKind.CLASS, JavaSymbolKind.INTERFACE, JavaSymbolKind.ENUM, JavaSymbolKind.ANNOTATION,
                JavaSymbolKind.RECORD -> true;
            default -> false;
        };
    }

    private @Nullable String superTypeQualifiedName(
        @Nullable String ownerQualifiedName,
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        List<String> superTypes = directSuperTypes(ownerQualifiedName, model, projectIndex);
        return superTypes.isEmpty() ? null : superTypes.get(0);
    }

    private List<String> directSuperTypes(
        @Nullable String ownerQualifiedName,
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (ownerQualifiedName == null || ownerQualifiedName.isBlank())
            return List.of();

        ClassStub stub = lookupBinaryClassStub(ownerQualifiedName, projectIndex);
        if (stub != null) {
            List<String> result = new ArrayList<>();
            if (stub.superClass() instanceof dev.railroadide.railroad.ide.classparser.Type.ClassType classType) {
                result.add(classType.name());
            }

            for (dev.railroadide.railroad.ide.classparser.Type iface : stub.interfaces()) {
                ActivityCheckpoint.poll(activityCheck);
                if (iface instanceof dev.railroadide.railroad.ide.classparser.Type.ClassType classType) {
                    result.add(classType.name());
                }
            }

            return List.copyOf(result);
        }

        TypeDeclarationInfo declarationInfo = typeDeclarationInfoForOwner(ownerQualifiedName, model, projectIndex);
        if (declarationInfo == null)
            return List.of();

        if (!declarationInfo.directSupertypes().isEmpty())
            return declarationInfo.directSupertypes();

        return implicitSuperTypes(ownerQualifiedName, declarationInfo.kind());
    }

    private List<String> implicitSuperTypes(String ownerQualifiedName, SymbolKind kind) {
        return switch (kind) {
            case JavaSymbolKind.CLASS ->
                "java.lang.Object".equals(ownerQualifiedName) ? List.of() : List.of("java.lang.Object");
            case JavaSymbolKind.RECORD -> List.of("java.lang.Record");
            case JavaSymbolKind.ENUM -> List.of("java.lang.Enum");
            case JavaSymbolKind.ANNOTATION -> List.of("java.lang.annotation.Annotation");
            case JavaSymbolKind.INTERFACE -> List.of();
            default -> List.of();
        };
    }

    private @Nullable TypeDeclarationInfo typeDeclarationInfoForOwner(
        String ownerQualifiedName,
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        SourceTypeDeclaration declaration = sourceTypeDeclaration(ownerQualifiedName, model, projectIndex);
        if (declaration == null)
            return null;

        SyntaxNode declarationNode = declaration.node();
        SemanticModel declarationModel = declaration.model();
        List<String> directSupertypes = new ArrayList<>();
        collectClauseTypes(
            directSupertypes,
            JavaSemanticAnalyzer.directChild(declarationNode, JavaSyntaxKinds.EXTENDS_CLAUSE.id()),
            declarationNode,
            declarationModel,
            projectIndex);
        collectClauseTypes(
            directSupertypes,
            JavaSemanticAnalyzer.directChild(declarationNode, JavaSyntaxKinds.IMPLEMENTS_CLAUSE.id()),
            declarationNode,
            declarationModel,
            projectIndex);
        return new TypeDeclarationInfo(declaration.kind(), List.copyOf(directSupertypes));
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
    private SourceTypeDeclaration sourceTypeDeclaration(
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
    private SourceTypeDeclaration sourceTypeDeclarationInModel(
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

    private void collectClauseTypes(
        List<String> out,
        @Nullable SyntaxNode clauseNode,
        SyntaxNode usageSite,
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (clauseNode == null)
            return;

        for (SyntaxNode typeReference : descendantTypeReferences(clauseNode)) {
            ActivityCheckpoint.poll(activityCheck);
            String qualifiedName = resolveQualifiedTypeName(
                JavaSemanticAnalyzer.canonicalTypeText(typeReference),
                usageSite,
                model,
                projectIndex);
            if (qualifiedName != null && !qualifiedName.isBlank() && !out.contains(qualifiedName)) {
                out.add(qualifiedName);
            }
        }
    }

    private List<SyntaxNode> descendantTypeReferences(SyntaxNode root) {
        List<SyntaxNode> result = new ArrayList<>();
        collectDescendantTypeReferences(root, result);
        return List.copyOf(result);
    }

    private void collectDescendantTypeReferences(SyntaxNode node, List<SyntaxNode> out) {
        if (JavaSyntaxKinds.TYPE_REFERENCE.id().equals(node.kind().id())) {
            out.add(node);
            return;
        }

        for (SyntaxNode child : node.children()) {
            ActivityCheckpoint.poll(activityCheck);
            collectDescendantTypeReferences(child, out);
        }
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
    private String resolveQualifiedTypeName(
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

        if (text.indexOf('.') > 0)
            return text;

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
    private ClassStub lookupBinaryClassStub(
        String qualifiedName,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (qualifiedName == null || qualifiedName.isBlank())
            return null;

        if (projectIndex != null)
            return projectIndex.lookupClassStub(qualifiedName);

        return JavaSemanticAnalyzer.loadJdkClassStubsByQualifiedName(activityCheck).get(qualifiedName);
    }

    private @Nullable SyntaxNode findDeepestNodeContaining(SyntaxNode node, int offset) {
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

    private String currentPackageName(SyntaxNode root) {
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

    private @Nullable String ownerQualifiedName(@Nullable String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank())
            return null;

        int separator = qualifiedName.indexOf('#');
        return separator > 0 ? qualifiedName.substring(0, separator) : null;
    }

    private String renderJvmType(@Nullable dev.railroadide.railroad.ide.classparser.Type type) {
        if (type == null)
            return "?";

        return switch (type) {
            case dev.railroadide.railroad.ide.classparser.Type.PrimitiveType primitive -> primitive.name();
            case dev.railroadide.railroad.ide.classparser.Type.ArrayType array ->
                renderJvmType(array.componentType()) + "[]";
            case dev.railroadide.railroad.ide.classparser.Type.ClassType clazz -> clazz.name();
            case dev.railroadide.railroad.ide.classparser.Type.TypeVariable variable -> variable.name();
            case dev.railroadide.railroad.ide.classparser.Type.WildcardType wildcard ->
                wildcard.bound() == null ? "?" : "? " + renderJvmType(wildcard.bound());
        };
    }

    private record TypeDeclarationInfo(SymbolKind kind, List<String> directSupertypes) {
    }

    /**
     * A type's source declaration together with the semantic model it belongs to.
     *
     * @param model model owning the declaration node
     * @param node type declaration node
     * @param kind declared type kind
     */
    private record SourceTypeDeclaration(SemanticModel model, SyntaxNode node, SymbolKind kind) {
    }

    private record CompletionTarget(String ownerQualifiedName, boolean staticContext) {
    }

    /**
     * Suggests the inherited methods that the enclosing type can override at a member-declaration position.
     * An accepted suggestion inserts an {@code @Override} declaration whose body delegates to the inherited
     * implementation or, for an abstract method, returns a default value.
     */
    private final class OverrideCompletion {
        private static final Set<String> TYPE_BODY_KIND_IDS = Set.of(
            JavaSyntaxKinds.CLASS_BODY.id(),
            JavaSyntaxKinds.INTERFACE_BODY.id(),
            JavaSyntaxKinds.ENUM_BODY.id(),
            JavaSyntaxKinds.RECORD_BODY.id());
        private static final Set<String> PRIMITIVE_TOKEN_KIND_IDS = Set.of(
            tokenKindId(JavaTokenType.BOOLEAN_KEYWORD),
            tokenKindId(JavaTokenType.BYTE_KEYWORD),
            tokenKindId(JavaTokenType.SHORT_KEYWORD),
            tokenKindId(JavaTokenType.CHAR_KEYWORD),
            tokenKindId(JavaTokenType.INT_KEYWORD),
            tokenKindId(JavaTokenType.LONG_KEYWORD),
            tokenKindId(JavaTokenType.FLOAT_KEYWORD),
            tokenKindId(JavaTokenType.DOUBLE_KEYWORD),
            tokenKindId(JavaTokenType.VOID_KEYWORD));
        private static final Pattern SYNTHETIC_PARAMETER_NAME = Pattern.compile("arg\\d+");
        private static final String OBJECT = "java.lang.Object";
        private static final int ACC_BRIDGE = 0x40;
        private static final int ACC_VARARGS = 0x80;
        private static final int ACC_SYNTHETIC = 0x1000;

        private final String document;
        private final @Nullable JavaSymbolIndex projectIndex;
        private final Map<String, Optional<TypeInfo>> typeInfos = new HashMap<>();
        private final Map<String, RenderedName> renderedNames = new HashMap<>();
        private final Map<String, String> itemImports = new TreeMap<>();
        private SemanticModel model;
        private SyntaxNode typeDeclaration;
        private String currentPackage;

        private OverrideCompletion(String document, @Nullable JavaSymbolIndex projectIndex) {
            this.document = document;
            this.projectIndex = projectIndex;
        }

        /**
         * Computes override suggestions for a member-declaration position.
         *
         * @param triggerAt the first character of the typed member name, or the character before the caret when no
         *            name has been typed
         * @return override suggestions replacing the typed name, or null outside a member-declaration position
         */
        private @Nullable CompletionResult compute(int triggerAt) {
            int prefixStart = triggerAt + 1;
            if (Character.isJavaIdentifierPart(document.charAt(triggerAt))) {
                prefixStart = triggerAt;
                while (prefixStart > 0 && Character.isJavaIdentifierPart(document.charAt(prefixStart - 1))) {
                    prefixStart--;
                }

                if (!Character.isJavaIdentifierStart(document.charAt(prefixStart)))
                    return null;
            }

            SyntaxTree tree = JavaSyntaxParser.parse(document, activityCheck);
            if (memberPositionBody(tree.root(), prefixStart) == null)
                return null;

            model = JavaSemanticAnalyzer.analyzeFacts(tree, projectIndex, activityCheck);
            SyntaxNode body = memberPositionBody(model.syntaxTree().root(), prefixStart);
            typeDeclaration = body == null ? null : body.parent().orElse(null);
            Symbol type = typeDeclaration == null ? null : model.declaredSymbol(typeDeclaration).orElse(null);
            String owner = type == null ? null : type.qualifiedName().orElse(null);
            if (owner == null || type.kind() == JavaSymbolKind.ANNOTATION)
                return null;

            currentPackage = currentPackageName(model.syntaxTree().root());
            TypeInfo current = sourceTypeInfo(new SourceTypeDeclaration(model, typeDeclaration, type.kind()));
            Set<String> declared = new HashSet<>();
            for (Method method : current.methods()) {
                declared.add(method.erasedSignature(Map.of()));
            }

            Map<String, Optional<Candidate>> candidates = new LinkedHashMap<>();
            Set<String> visited = new HashSet<>(Set.of(owner));
            for (TypeExpr superType : current.superTypes()) {
                if (superType instanceof TypeExpr.Named named) {
                    collect(named, true, declared, candidates, visited);
                }
            }

            boolean interfaceTarget = current.isInterface();
            List<CompletionItem> items = candidates.values().stream()
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing((Candidate candidate) -> candidate.method().name(),
                    String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(candidate -> candidate.method().parameters().size()))
                .map(candidate -> item(candidate, interfaceTarget))
                .toList();
            return items.isEmpty() ? null : new CompletionResult(prefixStart, items);
        }

        private @Nullable SyntaxNode memberPositionBody(SyntaxNode root, int prefixStart) {
            if (prefixStart < document.length() && Character.isJavaIdentifierStart(document.charAt(prefixStart)))
                return typedMemberBody(root, prefixStart);

            if (prefixStart == 0)
                return null;

            SyntaxNode node = deepestNodeContaining(root, prefixStart - 1);
            if (!(node instanceof SyntaxToken token))
                return null;

            SyntaxNode parent = token.parent().orElse(null);
            if (isTypeBody(parent)) {
                String kindId = token.kind().id();
                boolean boundary = JavaSemanticAnalyzer.isTriviaToken(token)
                    || kindId.equals(tokenKindId(JavaTokenType.OPEN_BRACE))
                    || kindId.equals(tokenKindId(JavaTokenType.SEMICOLON));
                return boundary ? parent : null;
            }

            SyntaxNode member = token;
            while (parent != null && !isTypeBody(parent) && parent.end() == token.end()) {
                member = parent;
                parent = parent.parent().orElse(null);
            }

            return isTypeBody(parent) && !(member instanceof SyntaxToken) ? parent : null;
        }

        private @Nullable SyntaxNode typedMemberBody(SyntaxNode root, int prefixStart) {
            SyntaxNode node = deepestNodeContaining(root, prefixStart);
            if (!(node instanceof SyntaxToken token) || token.start() != prefixStart
                || !JavaSemanticAnalyzer.isIdentifierLikeToken(token))
                return null;

            SyntaxNode typeReference = token.parent().orElse(null);
            if (typeReference == null || !JavaSyntaxKinds.TYPE_REFERENCE.id().equals(typeReference.kind().id())
                || significantChildren(typeReference).size() != 1)
                return null;

            SyntaxNode member = typeReference.parent().orElse(null);
            if (member == null || !JavaSyntaxKinds.FIELD_DECLARATION.id().equals(member.kind().id())
                && !JavaSyntaxKinds.METHOD_DECLARATION.id().equals(member.kind().id()))
                return null;

            for (SyntaxNode child : member.children()) {
                if (child == typeReference)
                    break;

                if (!JavaSyntaxKinds.ANNOTATION.id().equals(child.kind().id())
                    && !(child instanceof SyntaxToken leading && JavaSemanticAnalyzer.isTriviaToken(leading)))
                    return null;
            }

            SyntaxNode body = member.parent().orElse(null);
            return isTypeBody(body) ? body : null;
        }

        private void collect(
            TypeExpr.Named reference,
            boolean direct,
            Set<String> declared,
            Map<String, Optional<Candidate>> candidates,
            Set<String> visited
        ) {
            ActivityCheckpoint.poll(activityCheck);
            if (!visited.add(reference.name()))
                return;

            TypeInfo info = typeInfo(reference.name());
            if (info == null)
                return;

            Map<String, TypeExpr> bindings = bind(info.typeParameters(), reference.arguments());
            for (Method method : info.methods()) {
                ActivityCheckpoint.poll(activityCheck);
                if (method.isStatic() || method.access() == Access.PRIVATE)
                    continue;

                if (method.access() == Access.PACKAGE && !Objects.equals(info.packageName(), currentPackage))
                    continue;

                String signature = method.erasedSignature(bindings);
                if (declared.contains(signature))
                    continue;

                candidates.putIfAbsent(signature, method.isFinal()
                    ? Optional.empty()
                    : Optional.of(new Candidate(method, bindings, reference.name(), info.isInterface(), direct)));
            }

            for (TypeExpr superType : info.superTypes()) {
                if (substitute(superType, bindings) instanceof TypeExpr.Named named) {
                    collect(named, false, declared, candidates, visited);
                }
            }
        }

        private static Map<String, TypeExpr> bind(List<TypeParameter> parameters, List<TypeExpr> arguments) {
            Map<String, TypeExpr> bindings = new HashMap<>();
            for (int index = 0; index < parameters.size(); index++) {
                TypeParameter parameter = parameters.get(index);
                bindings.put(parameter.name(), arguments.size() == parameters.size()
                    ? arguments.get(index)
                    : new TypeExpr.Named(parameter.erasure(), List.of()));
            }

            return bindings;
        }

        private CompletionItem item(Candidate candidate, boolean interfaceTarget) {
            itemImports.clear();
            Method method = candidate.method();
            Map<String, TypeExpr> bindings = new HashMap<>(candidate.bindings());
            method.typeParameters().forEach(parameter -> bindings.remove(parameter.name()));

            List<TypeExpr> types = method.parameters().stream()
                .map(parameter -> substitute(parameter.type(), bindings))
                .toList();
            List<String> names = parameterNames(method.parameters(), types);
            List<String> parameterTypes = new ArrayList<>();
            List<String> declarations = new ArrayList<>();
            for (int index = 0; index < method.parameters().size(); index++) {
                Parameter parameter = method.parameters().get(index);
                TypeExpr type = types.get(index);
                String rendered = parameter.varargs() && type instanceof TypeExpr.Array array
                    ? render(array.component()) + "..."
                    : render(type);
                parameterTypes.add(rendered);
                declarations.add(rendered + " " + names.get(index));
            }

            var header = new StringBuilder("@Override\n");
            if (interfaceTarget) {
                header.append("default ");
            } else if (method.access() != Access.PACKAGE) {
                header.append(method.access().keyword()).append(' ');
            }

            if (!method.typeParameters().isEmpty()) {
                header.append(method.typeParameters().stream()
                    .map(parameter -> renderTypeParameter(parameter, bindings))
                    .collect(Collectors.joining(", ", "<", "> ")));
            }

            TypeExpr returnType = substitute(method.returnType(), bindings);
            header.append(render(returnType)).append(' ').append(method.name())
                .append('(').append(String.join(", ", declarations)).append(')');
            if (!method.thrownTypes().isEmpty()) {
                header.append(method.thrownTypes().stream()
                    .map(thrown -> render(substitute(thrown, bindings)))
                    .collect(Collectors.joining(", ", " throws ", "")));
            }

            header.append(" {\n\t");
            int caretOffset = header.length();
            String text = header + bodyStatement(candidate, returnType, names) + "\n}";
            return CompletionItem.override(method.name(), String.join(", ", parameterTypes),
                simpleName(candidate.ownerName()), new CompletionItem.Template(text, caretOffset,
                    importInsertions(List.copyOf(itemImports.values()))));
        }

        /**
         * Places new single-type imports among the existing ones in name order, or after the package declaration
         * when the document has no imports.
         */
        private List<CompletionItem.Insertion> importInsertions(List<String> qualifiedNames) {
            if (qualifiedNames.isEmpty())
                return List.of();

            SyntaxNode root = model.syntaxTree().root();
            List<JavaImports.ImportDeclaration> imports = JavaImports.imports(root);
            Map<Integer, List<String>> before = new TreeMap<>();
            List<String> after = new ArrayList<>();
            for (String name : qualifiedNames.stream().sorted().toList()) {
                imports.stream()
                    .filter(declaration -> !declaration.isStatic() && declaration.target().compareTo(name) > 0)
                    .findFirst()
                    .ifPresentOrElse(
                        declaration -> before.computeIfAbsent(declaration.start(), _ -> new ArrayList<>()).add(name),
                        () -> after.add(name));
            }

            Map<Integer, String> insertions = new TreeMap<>();
            before.forEach((offset, names) -> insertions.put(offset, names.stream()
                .map(name -> "import " + name + ";\n")
                .collect(Collectors.joining())));
            if (!after.isEmpty()) {
                String lines = after.stream().map(name -> "import " + name + ";").collect(Collectors.joining("\n"));
                if (!imports.isEmpty()) {
                    insertions.merge(imports.getLast().end(), "\n" + lines, String::concat);
                } else {
                    SyntaxNode packageDeclaration = JavaSemanticAnalyzer.directChild(root,
                        JavaSyntaxKinds.PACKAGE_DECLARATION.id());
                    if (packageDeclaration != null) {
                        insertions.put(packageDeclaration.end(), "\n\n" + lines);
                    } else {
                        insertions.put(firstDeclarationStart(root), lines + "\n\n");
                    }
                }
            }

            return insertions.entrySet().stream()
                .map(entry -> new CompletionItem.Insertion(entry.getKey(), entry.getValue()))
                .toList();
        }

        private static int firstDeclarationStart(SyntaxNode root) {
            for (SyntaxNode child : root.children()) {
                if (!(child instanceof SyntaxToken))
                    return child.start();
            }

            return 0;
        }

        private String bodyStatement(Candidate candidate, TypeExpr returnType, List<String> argumentNames) {
            boolean returnsVoid = returnType instanceof TypeExpr.Primitive(String name) && "void".equals(name);
            String receiver = null;
            if (!candidate.method().isAbstract()) {
                if (!candidate.ownerIsInterface()) {
                    receiver = "super";
                } else if (candidate.direct()) {
                    receiver = renderName(candidate.ownerName()) + ".super";
                }
            }

            if (receiver == null)
                return returnsVoid ? "" : "return " + defaultValue(returnType) + ";";

            String call = receiver + "." + candidate.method().name() + "(" + String.join(", ", argumentNames) + ");";
            return returnsVoid ? call : "return " + call;
        }

        private static String defaultValue(TypeExpr type) {
            if (type instanceof TypeExpr.Primitive(String name))
                return "boolean".equals(name) ? "false" : "0";

            return "null";
        }

        private String renderTypeParameter(TypeParameter parameter, Map<String, TypeExpr> bindings) {
            List<String> bounds = parameter.bounds().stream()
                .filter(bound -> !(bound instanceof TypeExpr.Named(String name, List<TypeExpr> arguments)
                    && OBJECT.equals(name) && arguments.isEmpty()))
                .map(bound -> render(substitute(bound, bindings)))
                .toList();
            return bounds.isEmpty() ? parameter.name() : parameter.name() + " extends " + String.join(" & ", bounds);
        }

        private static List<String> parameterNames(List<Parameter> parameters, List<TypeExpr> types) {
            Set<String> used = new HashSet<>();
            List<String> names = new ArrayList<>();
            for (int index = 0; index < parameters.size(); index++) {
                String declared = parameters.get(index).name();
                String base = declared == null || SYNTHETIC_PARAMETER_NAME.matcher(declared).matches()
                    ? nameForType(types.get(index))
                    : declared;
                String name = base;
                for (int suffix = 2; !used.add(name); suffix++) {
                    name = base + suffix;
                }

                names.add(name);
            }

            return names;
        }

        private static String nameForType(TypeExpr type) {
            String name = switch (type) {
                case TypeExpr.Primitive primitive -> primitive.name().substring(0, 1);
                case TypeExpr.Variable variable -> variable.name().toLowerCase(Locale.ROOT);
                case TypeExpr.Array array -> (array.component() instanceof TypeExpr.Primitive primitive
                    ? primitive.name()
                    : nameForType(array.component())) + "s";
                case TypeExpr.Wildcard wildcard -> wildcard.bound() == null ? "value" : nameForType(wildcard.bound());
                case TypeExpr.Named named -> {
                    String simple = simpleName(named.name());
                    yield Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
                }
            };

            return SourceVersion.isKeyword(name)
                ? "a" + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                : name;
        }

        private String render(TypeExpr type) {
            return switch (type) {
                case TypeExpr.Primitive primitive -> primitive.name();
                case TypeExpr.Variable variable -> variable.name();
                case TypeExpr.Array array -> render(array.component()) + "[]";
                case TypeExpr.Wildcard wildcard -> {
                    TypeExpr bound = wildcard.bound();
                    if (bound == null
                        || wildcard.upper() && bound instanceof TypeExpr.Named(String name, List<TypeExpr> arguments)
                            && OBJECT.equals(name) && arguments.isEmpty())
                        yield "?";

                    yield (wildcard.upper() ? "? extends " : "? super ") + render(bound);
                }

                case TypeExpr.Named named -> named.arguments().isEmpty()
                    ? renderName(named.name())
                    : renderName(named.name()) + named.arguments().stream().map(this::render)
                        .collect(Collectors.joining(", ", "<", ">"));
            };
        }

        /**
         * Renders a type name as written in the current document, importing its top-level type into the current
         * suggestion when that is the only way to shorten it and the simple name is free.
         */
        private String renderName(String qualifiedName) {
            RenderedName rendered = renderedNames.computeIfAbsent(qualifiedName, this::resolveRenderedName);
            if (rendered.importName() == null)
                return rendered.text();

            String simple = simpleName(rendered.importName());
            String imported = itemImports.putIfAbsent(simple, rendered.importName());
            return imported == null || imported.equals(rendered.importName())
                ? rendered.importedText()
                : rendered.text();
        }

        private RenderedName resolveRenderedName(String qualifiedName) {
            String normalized = qualifiedName.replace('$', '.');
            String[] segments = normalized.split("\\.");
            String[] resolved = new String[segments.length];
            for (int index = segments.length - 1; index >= 0; index--) {
                String expected = String.join(".", Arrays.copyOfRange(segments, 0, index + 1));
                resolved[index] = resolveQualifiedTypeName(segments[index], typeDeclaration, model, projectIndex);
                if (resolved[index] != null && resolved[index].replace('$', '.').equals(expected))
                    return new RenderedName(String.join(".", Arrays.copyOfRange(segments, index, segments.length)),
                        null, "");
            }

            int topLevel = topLevelSegment(qualifiedName, segments);
            if (topLevel <= 0 || resolved[topLevel] != null)
                return new RenderedName(normalized, null, "");

            return new RenderedName(normalized, String.join(".", Arrays.copyOfRange(segments, 0, topLevel + 1)),
                String.join(".", Arrays.copyOfRange(segments, topLevel, segments.length)));
        }

        /**
         * Finds the segment naming the top-level type: the one before the first {@code $} of a binary name, or
         * else the first capitalised segment.
         */
        private static int topLevelSegment(String qualifiedName, String[] segments) {
            int binarySeparator = qualifiedName.indexOf('$');
            if (binarySeparator >= 0)
                return (int) qualifiedName.substring(0, binarySeparator).chars().filter(ch -> ch == '.').count();

            for (int index = 0; index < segments.length; index++) {
                if (!segments[index].isEmpty() && Character.isUpperCase(segments[index].charAt(0)))
                    return index;
            }

            return -1;
        }

        private static String simpleName(String qualifiedName) {
            int separator = Math.max(qualifiedName.lastIndexOf('.'), qualifiedName.lastIndexOf('$'));
            return qualifiedName.substring(separator + 1);
        }

        private static TypeExpr substitute(TypeExpr type, Map<String, TypeExpr> bindings) {
            if (bindings.isEmpty())
                return type;

            return switch (type) {
                case TypeExpr.Primitive primitive -> primitive;
                case TypeExpr.Variable variable -> bindings.getOrDefault(variable.name(), variable);
                case TypeExpr.Array array -> new TypeExpr.Array(substitute(array.component(), bindings));
                case TypeExpr.Wildcard wildcard -> new TypeExpr.Wildcard(
                    wildcard.bound() == null ? null : substitute(wildcard.bound(), bindings), wildcard.upper());
                case TypeExpr.Named named -> new TypeExpr.Named(named.name(),
                    named.arguments().stream().map(argument -> substitute(argument, bindings)).toList());
            };
        }

        private @Nullable TypeInfo typeInfo(String qualifiedName) {
            Optional<TypeInfo> cached = typeInfos.get(qualifiedName);
            if (cached != null)
                return cached.orElse(null);

            TypeInfo info = null;
            SourceTypeDeclaration local = sourceTypeDeclarationInModel(qualifiedName, model);
            ClassStub stub = local == null ? lookupBinaryClassStub(qualifiedName, projectIndex) : null;
            if (stub != null) {
                info = binaryTypeInfo(stub);
            } else {
                SourceTypeDeclaration declaration = local != null
                    ? local
                    : sourceTypeDeclaration(qualifiedName, model, projectIndex);
                if (declaration != null) {
                    info = sourceTypeInfo(declaration);
                }
            }

            typeInfos.put(qualifiedName, Optional.ofNullable(info));
            return info;
        }

        private TypeInfo binaryTypeInfo(ClassStub stub) {
            boolean isInterface = Modifier.isInterface(stub.modifiers());
            List<TypeExpr> superTypes = new ArrayList<>();
            if (!isInterface && stub.superClass() != null) {
                superTypes.add(fromBinary(stub.superClass()));
            }

            stub.interfaces().forEach(type -> superTypes.add(fromBinary(type)));
            List<Method> methods = new ArrayList<>();
            for (MethodStub method : stub.methods()) {
                ActivityCheckpoint.poll(activityCheck);
                int modifiers = method.modifiers();
                if (method.name().startsWith("<") || (modifiers & (ACC_BRIDGE | ACC_SYNTHETIC)) != 0)
                    continue;

                List<Parameter> parameters = new ArrayList<>();
                for (int index = 0; index < method.parameters().size(); index++) {
                    dev.railroadide.railroad.ide.classparser.stub.Parameter parameter = method.parameters().get(index);
                    boolean varargs = (modifiers & ACC_VARARGS) != 0 && index == method.parameters().size() - 1;
                    parameters.add(new Parameter(parameter.name(), fromBinary(parameter.type()), varargs));
                }

                methods.add(new Method(
                    method.name(),
                    binaryTypeParameters(method.typeParameters()),
                    fromBinary(method.returnType()),
                    List.copyOf(parameters),
                    method.thrownTypes().stream().map(OverrideCompletion::fromBinary).toList(),
                    Modifier.isPublic(modifiers)
                        ? Access.PUBLIC
                        : Modifier.isProtected(modifiers)
                            ? Access.PROTECTED
                            : Modifier.isPrivate(modifiers) ? Access.PRIVATE : Access.PACKAGE,
                    Modifier.isStatic(modifiers),
                    Modifier.isFinal(modifiers),
                    Modifier.isAbstract(modifiers)));
            }

            return new TypeInfo(binaryTypeParameters(stub.typeParameters()), List.copyOf(superTypes),
                List.copyOf(methods),
                isInterface, stub.packageName());
        }

        private static List<TypeParameter> binaryTypeParameters(
            List<dev.railroadide.railroad.ide.classparser.stub.TypeParameter> parameters
        ) {
            return parameters.stream()
                .map(parameter -> new TypeParameter(parameter.name(),
                    parameter.bounds().stream().map(OverrideCompletion::fromBinary).toList()))
                .toList();
        }

        private static TypeExpr fromBinary(dev.railroadide.railroad.ide.classparser.Type type) {
            return switch (type) {
                case dev.railroadide.railroad.ide.classparser.Type.PrimitiveType primitive ->
                    new TypeExpr.Primitive(primitive.name());
                case dev.railroadide.railroad.ide.classparser.Type.ArrayType array ->
                    new TypeExpr.Array(fromBinary(array.componentType()));
                case dev.railroadide.railroad.ide.classparser.Type.TypeVariable variable ->
                    new TypeExpr.Variable(variable.name());
                case dev.railroadide.railroad.ide.classparser.Type.WildcardType wildcard -> new TypeExpr.Wildcard(
                    wildcard.bound() == null ? null : fromBinary(wildcard.bound()), wildcard.isUpperBound());
                case dev.railroadide.railroad.ide.classparser.Type.ClassType classType ->
                    new TypeExpr.Named(classType.name(),
                        classType.typeArguments().stream().map(OverrideCompletion::fromBinary).toList());
            };
        }

        private TypeInfo sourceTypeInfo(SourceTypeDeclaration declaration) {
            SyntaxNode node = declaration.node();
            SemanticModel declarationModel = declaration.model();
            SymbolKind kind = declaration.kind();
            Symbol symbol = declarationModel.declaredSymbol(node).orElse(null);
            String qualifiedName = symbol == null ? "" : symbol.qualifiedName().orElse("");
            List<TypeParameter> typeParameters = sourceTypeParameters(node, Set.of(), declarationModel);
            Set<String> variables = typeParameters.stream().map(TypeParameter::name).collect(Collectors.toSet());

            List<TypeExpr> superTypes = new ArrayList<>();
            SyntaxNode extendsClause = JavaSemanticAnalyzer.directChild(node, JavaSyntaxKinds.EXTENDS_CLAUSE.id());
            if (extendsClause != null) {
                typeReferences(extendsClause)
                    .forEach(reference -> superTypes.add(fromSource(reference, variables, declarationModel)));
            } else if (kind == JavaSymbolKind.CLASS && !OBJECT.equals(qualifiedName)) {
                superTypes.add(new TypeExpr.Named(OBJECT, List.of()));
            } else if (kind == JavaSymbolKind.ENUM) {
                superTypes.add(new TypeExpr.Named("java.lang.Enum", List.of(new TypeExpr.Named(qualifiedName,
                    List.of()))));
            } else if (kind == JavaSymbolKind.RECORD) {
                superTypes.add(new TypeExpr.Named("java.lang.Record", List.of()));
            }

            SyntaxNode implementsClause = JavaSemanticAnalyzer.directChild(node,
                JavaSyntaxKinds.IMPLEMENTS_CLAUSE.id());
            if (implementsClause != null) {
                typeReferences(implementsClause)
                    .forEach(reference -> superTypes.add(fromSource(reference, variables, declarationModel)));
            }

            boolean isInterface = kind == JavaSymbolKind.INTERFACE || kind == JavaSymbolKind.ANNOTATION;
            List<Method> methods = new ArrayList<>();
            for (SyntaxNode child : node.children()) {
                if (!isTypeBody(child))
                    continue;

                for (SyntaxNode member : child.children()) {
                    ActivityCheckpoint.poll(activityCheck);
                    if (JavaSyntaxKinds.METHOD_DECLARATION.id().equals(member.kind().id())) {
                        Method method = sourceMethod(member, variables, isInterface, declarationModel);
                        if (method != null) {
                            methods.add(method);
                        }
                    }
                }
            }

            return new TypeInfo(typeParameters, List.copyOf(superTypes), List.copyOf(methods), isInterface,
                currentPackageName(declarationModel.syntaxTree().root()));
        }

        private @Nullable Method sourceMethod(
            SyntaxNode declaration,
            Set<String> typeVariables,
            boolean inInterface,
            SemanticModel declarationModel
        ) {
            Set<String> modifiers = new HashSet<>();
            String name = null;
            for (SyntaxNode child : declaration.children()) {
                if (child instanceof SyntaxToken token) {
                    if (name == null && JavaSemanticAnalyzer.isIdentifierLikeToken(token)) {
                        name = token.text();
                    } else {
                        modifiers.add(token.kind().id());
                    }
                }
            }

            if (name == null)
                return null;

            List<TypeParameter> typeParameters = sourceTypeParameters(declaration, typeVariables, declarationModel);
            Set<String> variables = new HashSet<>(typeVariables);
            typeParameters.forEach(parameter -> variables.add(parameter.name()));

            SyntaxNode returnReference = JavaSemanticAnalyzer.directChild(declaration,
                JavaSyntaxKinds.TYPE_REFERENCE.id());
            TypeExpr returnType = returnReference == null
                ? new TypeExpr.Primitive("void")
                : fromSource(returnReference, variables, declarationModel);

            List<Parameter> parameters = new ArrayList<>();
            SyntaxNode parameterList = JavaSemanticAnalyzer.directChild(declaration,
                JavaSyntaxKinds.PARAMETER_LIST.id());
            if (parameterList != null) {
                for (SyntaxNode parameter : parameterList.children()) {
                    if (!JavaSyntaxKinds.PARAMETER.id().equals(parameter.kind().id()))
                        continue;

                    SyntaxNode typeReference = JavaSemanticAnalyzer.directChild(parameter,
                        JavaSyntaxKinds.TYPE_REFERENCE.id());
                    if (typeReference == null)
                        return null;

                    boolean varargs = JavaSemanticAnalyzer.directChild(parameter,
                        tokenKindId(JavaTokenType.ELLIPSIS)) != null;
                    String parameterName = null;
                    for (SyntaxNode child : parameter.children()) {
                        if (child instanceof SyntaxToken token && JavaSemanticAnalyzer.isIdentifierLikeToken(token)) {
                            parameterName = token.text();
                        }
                    }

                    TypeExpr type = fromSource(typeReference, variables, declarationModel);
                    parameters.add(new Parameter(parameterName, varargs ? new TypeExpr.Array(type) : type, varargs));
                }
            }

            List<TypeExpr> thrownTypes = new ArrayList<>();
            SyntaxNode throwsClause = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.THROWS_CLAUSE.id());
            if (throwsClause != null) {
                typeReferences(throwsClause)
                    .forEach(reference -> thrownTypes.add(fromSource(reference, variables, declarationModel)));
            }

            boolean isStatic = modifiers.contains(tokenKindId(JavaTokenType.STATIC_KEYWORD));
            boolean hasBody = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.BLOCK.id()) != null;
            Access access = modifiers.contains(tokenKindId(JavaTokenType.PRIVATE_KEYWORD))
                ? Access.PRIVATE
                : modifiers.contains(tokenKindId(JavaTokenType.PUBLIC_KEYWORD)) || inInterface
                    ? Access.PUBLIC
                    : modifiers.contains(tokenKindId(JavaTokenType.PROTECTED_KEYWORD))
                        ? Access.PROTECTED
                        : Access.PACKAGE;
            return new Method(
                name,
                typeParameters,
                returnType,
                List.copyOf(parameters),
                List.copyOf(thrownTypes),
                access,
                isStatic,
                modifiers.contains(tokenKindId(JavaTokenType.FINAL_KEYWORD)),
                modifiers.contains(tokenKindId(JavaTokenType.ABSTRACT_KEYWORD)) || inInterface && !hasBody);
        }

        private List<TypeParameter> sourceTypeParameters(
            SyntaxNode declaration,
            Set<String> enclosingVariables,
            SemanticModel declarationModel
        ) {
            SyntaxNode list = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.TYPE_PARAMETERS.id());
            if (list == null)
                return List.of();

            Set<String> variables = new HashSet<>(enclosingVariables);
            List<SyntaxNode> parameterNodes = new ArrayList<>();
            for (SyntaxNode child : list.children()) {
                if (JavaSyntaxKinds.TYPE_PARAMETER.id().equals(child.kind().id())) {
                    String name = firstIdentifier(child);
                    if (name != null) {
                        variables.add(name);
                        parameterNodes.add(child);
                    }
                }
            }

            List<TypeParameter> parameters = new ArrayList<>();
            for (SyntaxNode parameter : parameterNodes) {
                List<TypeExpr> bounds = new ArrayList<>();
                for (SyntaxNode child : parameter.children()) {
                    if (JavaSyntaxKinds.TYPE_BOUND.id().equals(child.kind().id())) {
                        typeReferences(child)
                            .forEach(bound -> bounds.add(fromSource(bound, variables, declarationModel)));
                    }
                }

                parameters
                    .add(new TypeParameter(Objects.requireNonNull(firstIdentifier(parameter)), List.copyOf(bounds)));
            }

            return List.copyOf(parameters);
        }

        private TypeExpr fromSource(SyntaxNode node, Set<String> variables, SemanticModel declarationModel) {
            if (JavaSyntaxKinds.WILDCARD_TYPE.id().equals(node.kind().id())) {
                boolean upper = JavaSemanticAnalyzer.directChild(node,
                    tokenKindId(JavaTokenType.SUPER_KEYWORD)) == null;
                SyntaxNode boundNode = JavaSemanticAnalyzer.directChild(node, JavaSyntaxKinds.TYPE_BOUND.id());
                List<SyntaxNode> bound = typeReferences(boundNode == null ? node : boundNode);
                return new TypeExpr.Wildcard(bound.isEmpty()
                    ? null
                    : fromSource(bound.getFirst(), variables,
                        declarationModel),
                    upper);
            }

            int dimensions = 0;
            SyntaxNode component = null;
            var name = new StringBuilder();
            List<TypeExpr> arguments = List.of();
            for (SyntaxNode child : node.children()) {
                String kindId = child.kind().id();
                if (JavaSyntaxKinds.ARRAY_DIMENSION.id().equals(kindId)) {
                    dimensions++;
                } else if (JavaSyntaxKinds.TYPE_REFERENCE.id().equals(kindId)) {
                    component = child;
                } else if (JavaSyntaxKinds.TYPE_ARGUMENTS.id().equals(kindId)) {
                    arguments = significantChildren(child).stream()
                        .filter(argument -> JavaSyntaxKinds.TYPE_REFERENCE.id().equals(argument.kind().id())
                            || JavaSyntaxKinds.WILDCARD_TYPE.id().equals(argument.kind().id()))
                        .map(argument -> fromSource(argument, variables, declarationModel))
                        .toList();
                } else if (child instanceof SyntaxToken token) {
                    if (PRIMITIVE_TOKEN_KIND_IDS.contains(kindId))
                        return wrapArray(new TypeExpr.Primitive(token.text()), dimensions);

                    if (JavaSemanticAnalyzer.isIdentifierLikeToken(token)
                        || kindId.equals(tokenKindId(JavaTokenType.DOT))) {
                        name.append(token.text());
                    }
                }
            }

            if (component != null)
                return wrapArray(fromSource(component, variables, declarationModel), dimensions);

            String text = name.toString();
            if (variables.contains(text))
                return wrapArray(new TypeExpr.Variable(text), dimensions);

            String qualified = resolveQualifiedTypeName(text, node, declarationModel, projectIndex);
            return wrapArray(new TypeExpr.Named(qualified == null ? text : qualified, arguments), dimensions);
        }

        private static TypeExpr wrapArray(TypeExpr type, int dimensions) {
            for (int index = 0; index < dimensions; index++) {
                type = new TypeExpr.Array(type);
            }

            return type;
        }

        private static List<SyntaxNode> typeReferences(SyntaxNode node) {
            return node.children().stream()
                .filter(child -> JavaSyntaxKinds.TYPE_REFERENCE.id().equals(child.kind().id()))
                .toList();
        }

        private static @Nullable String firstIdentifier(SyntaxNode node) {
            for (SyntaxNode child : node.children()) {
                if (child instanceof SyntaxToken token && JavaSemanticAnalyzer.isIdentifierLikeToken(token))
                    return token.text();
            }

            return null;
        }

        private static List<SyntaxNode> significantChildren(SyntaxNode node) {
            return node.children().stream()
                .filter(child -> !(child instanceof SyntaxToken token && (JavaSemanticAnalyzer.isTriviaToken(token)
                    || JavaSemanticAnalyzer.isMissingTokenKind(token.kind().id()))))
                .toList();
        }

        private static boolean isTypeBody(@Nullable SyntaxNode node) {
            return node != null && TYPE_BODY_KIND_IDS.contains(node.kind().id());
        }

        private @Nullable SyntaxNode deepestNodeContaining(SyntaxNode node, int offset) {
            if (offset < node.start() || offset >= node.end())
                return null;

            for (SyntaxNode child : node.children()) {
                ActivityCheckpoint.poll(activityCheck);
                SyntaxNode match = deepestNodeContaining(child, offset);
                if (match != null)
                    return match;
            }

            return node;
        }

        private static String tokenKindId(JavaTokenType type) {
            return JavaSyntaxKinds.tokenKind(type).id();
        }

        private enum Access {
            PUBLIC("public"),
            PROTECTED("protected"),
            PACKAGE(""),
            PRIVATE("private");

            private final String keyword;

            Access(String keyword) {
                this.keyword = keyword;
            }

            private String keyword() {
                return keyword;
            }
        }

        /**
         * Type syntax independent of whether it was read from source or a class file. Names are qualified when they
         * resolve; nested binary names keep their {@code $} separators.
         */
        private sealed interface TypeExpr {
            record Primitive(String name) implements TypeExpr {
            }

            record Named(String name, List<TypeExpr> arguments) implements TypeExpr {
            }

            record Variable(String name) implements TypeExpr {
            }

            record Array(TypeExpr component) implements TypeExpr {
            }

            record Wildcard(@Nullable TypeExpr bound, boolean upper) implements TypeExpr {
            }
        }

        private record TypeParameter(String name, List<TypeExpr> bounds) {
            private String erasure() {
                return bounds.isEmpty() || !(bounds.getFirst() instanceof TypeExpr.Named named) ? OBJECT : named.name();
            }
        }

        private record Parameter(@Nullable String name, TypeExpr type, boolean varargs) {
        }

        private record Method(
            String name,
            List<TypeParameter> typeParameters,
            TypeExpr returnType,
            List<Parameter> parameters,
            List<TypeExpr> thrownTypes,
            Access access,
            boolean isStatic,
            boolean isFinal,
            boolean isAbstract
        ) {
            /**
             * Builds the override-equivalence key from simple erased parameter type names.
             */
            private String erasedSignature(Map<String, TypeExpr> bindings) {
                return parameters.stream()
                    .map(parameter -> erasure(substitute(parameter.type(), bindings)))
                    .collect(Collectors.joining(",", name + "(", ")"));
            }

            private String erasure(TypeExpr type) {
                return switch (type) {
                    case TypeExpr.Primitive primitive -> primitive.name();
                    case TypeExpr.Array array -> erasure(array.component()) + "[]";
                    case TypeExpr.Named named -> simpleName(named.name());
                    case TypeExpr.Wildcard _ -> "Object";
                    case TypeExpr.Variable variable -> typeParameters.stream()
                        .filter(parameter -> parameter.name().equals(variable.name()))
                        .findFirst()
                        .map(parameter -> simpleName(parameter.erasure()))
                        .orElse("Object");
                };
            }
        }

        private record TypeInfo(
            List<TypeParameter> typeParameters,
            List<TypeExpr> superTypes,
            List<Method> methods,
            boolean isInterface,
            String packageName
        ) {
        }

        /**
         * How a type name is written in the current document.
         *
         * @param text name as written without adding an import
         * @param importName qualified top-level type whose import would shorten the name, or null
         * @param importedText name as written once {@code importName} is imported
         */
        private record RenderedName(String text, @Nullable String importName, String importedText) {
        }

        private record Candidate(
            Method method,
            Map<String, TypeExpr> bindings,
            String ownerName,
            boolean ownerIsInterface,
            boolean direct
        ) {
        }
    }
}
