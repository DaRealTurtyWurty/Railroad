package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.sst.java.api.JavaSymbolKind;
import dev.railroadide.railroad.ide.sst.java.api.JavaTypes;
import dev.railroadide.railroad.ide.sst.impl.java.JavaTypeLookup.SourceTypeDeclaration;
import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.sst.request.internal.ActivityCheckpoint;
import dev.railroadide.railroad.ide.classparser.stub.FieldStub;
import dev.railroadide.railroad.ide.classparser.stub.MethodStub;
import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import dev.railroadide.railroad.ide.sst.project.JavaProjectSemanticIndex;
import dev.railroadide.railroad.ide.sst.project.JavaSymbolIndex;
import dev.railroadide.railroad.ide.sst.semantic.api.SemanticModel;
import dev.railroadide.railroad.ide.sst.semantic.api.Symbol;
import dev.railroadide.railroad.ide.sst.semantic.api.SymbolKind;
import dev.railroadide.railroad.ide.sst.semantic.api.Type;
import dev.railroadide.railroad.ide.sst.semantic.api.TypeReference;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxTree;
import dev.railroadide.railroad.plugin.spi.inspection.JavaRuleContext;
import dev.railroadide.railroad.plugin.spi.inspection.JavaRuleContext.FieldDescriptor;
import dev.railroadide.railroad.plugin.spi.inspection.JavaRuleContext.MethodDescriptor;
import dev.railroadide.railroad.utility.NameMatcher;
import org.jetbrains.annotations.Nullable;

import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxToken;
import java.util.*;
import java.lang.reflect.Modifier;

/**
 * SST-backed completion for members, local variables, types and method overrides.
 */
public final class JavaSemanticCompletionEngine {
    private final Runnable activityCheck;
    private final JavaTypeLookup lookup;

    private JavaSemanticCompletionEngine(Runnable activityCheck) {
        this.activityCheck = new ActivityCheckpoint(activityCheck);
        this.lookup = new JavaTypeLookup(this.activityCheck);
    }

    /**
     * Computes accessible members and method references, unqualified names for a typed prefix, and overridable
     * methods at member-declaration positions.
     *
     * @param document the Java document text
     * @param triggerAt the zero-based offset of the triggering dot, of the first character of the typed member
     *            name, or of the character before the caret
     * @param projectIndex the project symbol index, or {@code null} to use the standard library
     * @return sorted completions, or {@code null} when the trigger yields no candidates
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

        SyntaxTree tree = JavaSyntaxParser.parse(document, activityCheck);
        SyntaxNode token = lookup.findDeepestNodeContaining(tree.root(), triggerAt);
        SyntaxNode parent = token == null ? null : token.parent().orElse(null);
        if (token instanceof SyntaxToken syntaxToken && parent != null
            && parent.kind().equals(JavaSyntaxKinds.METHOD_REFERENCE_EXPRESSION))
            return completeMethodReference(tree, parent, syntaxToken, triggerAt, projectIndex);

        if (document.charAt(triggerAt) == ':' && triggerAt > 0 && document.charAt(triggerAt - 1) == ':')
            return null;

        if (document.charAt(triggerAt) != '.') {
            CompletionResult overrides = new JavaMethodOverrides(document, projectIndex, activityCheck)
                .complete(tree, triggerAt);
            CompletionResult unqualified = completeUnqualified(tree, document, token, triggerAt, projectIndex);
            CompletionResult types = JavaTypeCompletion.complete(tree, triggerAt, projectIndex, activityCheck);
            return merge(overrides, merge(unqualified, types));
        }

        CompletionResult qualifiedType = JavaTypeCompletion.completeQualifiedType(tree, triggerAt, projectIndex,
            activityCheck);
        if (qualifiedType != null)
            return qualifiedType;

        SyntaxNode access = findMemberAccess(token);
        SyntaxNode receiver = access == null ? null : JavaSemanticAnalyzer.explicitReceiver(access);
        if (receiver == null)
            return null;

        var analysis = JavaSemanticAnalyzer.analyzeMemberCompletion(tree, receiver, projectIndex, activityCheck);
        SemanticModel model = analysis.model();
        String currentPackage = lookup.currentPackageName(tree.root());
        Map<String, CompletionItem> itemsByKey = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        if (analysis.receiverBounds().isEmpty()) {
            CompletionTarget target = completionTargetForReceiver(receiver, access, model, projectIndex);
            if (target == null || target.ownerQualifiedName() == null || target.ownerQualifiedName().isBlank())
                return null;

            collectMembersRecursive(model, projectIndex, target.ownerQualifiedName(), target.staticContext(),
                currentPackage, itemsByKey, visited);
            if (target.staticContext()) {
                for (CompletionItem item : JavaTypeCompletion.memberTypes(model, access, target.ownerQualifiedName(),
                    projectIndex, activityCheck)) {
                    itemsByKey.putIfAbsent("T:" + item.insertText(), item);
                }
            }
        } else {
            for (String bound : analysis.receiverBounds()) {
                collectMembersRecursive(model, projectIndex, bound, false, currentPackage, itemsByKey, visited);
            }
        }

        if (itemsByKey.isEmpty())
            return null;

        List<CompletionItem> items = itemsByKey.values().stream()
            .sorted(Comparator.comparing(CompletionItem::displayText, String.CASE_INSENSITIVE_ORDER))
            .toList();
        return new CompletionResult(triggerAt + 1, items);
    }

    private @Nullable CompletionResult completeUnqualified(
        SyntaxTree tree,
        String document,
        @Nullable SyntaxNode token,
        int triggerAt,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        if (!Character.isJavaIdentifierPart(document.charAt(triggerAt)))
            return null;

        if (!(token instanceof SyntaxToken syntaxToken) || JavaSemanticAnalyzer.isTriviaToken(syntaxToken)
            || !Character.isJavaIdentifierStart(document.charAt(token.start())))
            return null;

        SyntaxNode usage = token.parent().orElse(null);
        if (usage == null || !usage.kind().equals(JavaSyntaxKinds.NAME_EXPRESSION))
            return null;

        boolean callName = JavaSemanticAnalyzer.isSelectorNameExpression(usage);
        if (callName) {
            SyntaxNode parent = usage.parent().orElseThrow();
            if (!parent.kind().equals(JavaSyntaxKinds.METHOD_INVOCATION_EXPRESSION)
                || JavaSemanticAnalyzer.explicitReceiver(parent) != null)
                return null;
        }

        String prefix = document.substring(token.start(), triggerAt + 1);
        var analysis = JavaSemanticAnalyzer.analyzeValueCompletion(tree, usage, prefix, projectIndex, activityCheck);
        var rules = new JavaRuleContext(tree.documentUri(), document, analysis.model(), projectIndex, activityCheck);
        List<EnclosingType> enclosingTypes = enclosingTypes(analysis.model(), rules, usage);
        List<CompletionItem> items = new ArrayList<>();
        if (!callName) {
            analysis.localTypes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> CompletionItem.field(entry.getKey(), entry.getValue(), false))
                .forEach(items::add);
            Map<String, FieldDescriptor> fields = visibleFields(enclosingTypes, rules, usage, prefix,
                analysis.localTypes().keySet());
            fields.values().stream()
                .sorted(Comparator.comparing(FieldDescriptor::name, String.CASE_INSENSITIVE_ORDER))
                .map(field -> CompletionItem.field(field.name(),
                    field.type().kind() == JavaTypes.Kind.UNKNOWN ? "" : field.type().displayName(),
                    Modifier.isStatic(field.modifiers())))
                .forEach(items::add);
        }

        if (!enclosingTypes.isEmpty()) {
            items.addAll(declaredMethods(enclosingTypes.getFirst(), rules, prefix));
        }

        return items.isEmpty() ? null : new CompletionResult(token.start(), items);
    }

    private @Nullable CompletionResult completeMethodReference(
        SyntaxTree tree,
        SyntaxNode reference,
        SyntaxToken token,
        int triggerAt,
        @Nullable JavaSymbolIndex projectIndex
    ) {
        SyntaxNode separator = JavaSemanticAnalyzer.directChild(reference,
            JavaSyntaxKinds.tokenKind(JavaTokenType.DOUBLE_COLON).id());
        if (separator == null || token != separator && (token.start() < separator.end()
            || JavaSemanticAnalyzer.isTriviaToken(token)))
            return null;

        SyntaxNode receiver = reference.children().getFirst();
        if (receiver == separator)
            return null;

        String document = tree.documentSnapshot().text();
        int replacementStart = token == separator ? separator.end() : token.start();
        String prefix = token == separator ? "" : document.substring(replacementStart, triggerAt + 1);
        String typeText = receiver.kind().equals(JavaSyntaxKinds.TYPE_REFERENCE)
            ? JavaSemanticAnalyzer.canonicalTypeText(receiver)
            : null;
        if (typeText != null && typeText.endsWith("[]"))
            return NameMatcher.matches(prefix, "new")
                ? new CompletionResult(replacementStart,
                    List.of(CompletionItem.methodReference("new", "int", typeText, false)))
                : null;

        var analysis = JavaSemanticAnalyzer.analyzeMemberCompletion(tree, receiver, projectIndex, activityCheck);
        SemanticModel model = analysis.model();
        var rules = new JavaRuleContext(tree.documentUri(), document, model, projectIndex, activityCheck);
        Map<String, CompletionItem> items = new LinkedHashMap<>();
        if (analysis.receiverBounds().isEmpty()) {
            CompletionTarget target = typeText == null
                ? completionTargetForReceiver(receiver, reference, model, projectIndex)
                : completionTarget(rules.resolveQualifiedTypeName(typeText, reference), true);
            if (target == null)
                return null;

            String owner = target.ownerQualifiedName();
            collectReferenceMethods(rules, reference, owner, target.staticContext(), prefix, items);
            if (target.staticContext() && NameMatcher.matches(prefix, "new")) {
                for (MethodDescriptor constructor : rules.referenceConstructors(owner, reference)) {
                    addReferenceMethod(items, rules, constructor, "new");
                }
            }
        } else {
            for (String bound : analysis.receiverBounds()) {
                collectReferenceMethods(rules, reference, bound, false, prefix, items);
            }
        }

        if (items.isEmpty())
            return null;

        List<CompletionItem> ordered = items.values().stream()
            .sorted(Comparator.comparing(CompletionItem::insertText, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CompletionItem::suffix))
            .toList();
        return new CompletionResult(replacementStart, ordered);
    }

    private void collectReferenceMethods(
        JavaRuleContext rules,
        SyntaxNode reference,
        String owner,
        boolean typeQualifier,
        String prefix,
        Map<String, CompletionItem> items
    ) {
        List<MethodDescriptor> methods = new ArrayList<>(rules.methodDescriptorsForType(owner));
        methods.addAll(rules.inheritedMethodDescriptors(owner));
        for (MethodDescriptor method : methods) {
            ActivityCheckpoint.poll(activityCheck);
            if (!NameMatcher.matches(prefix, method.name()))
                continue;

            boolean declared = owner.equals(method.ownerQualifiedName());
            boolean isStatic = Modifier.isStatic(method.modifiers());
            if (isStatic && (!typeQualifier || !declared && rules.isInterfaceType(method.ownerQualifiedName()))
                || !declared && Modifier.isPrivate(method.modifiers())
                || !rules.isMethodAccessible(method, reference, owner))
                continue;

            addReferenceMethod(items, rules, method, method.name());
        }
    }

    private void addReferenceMethod(
        Map<String, CompletionItem> items,
        JavaRuleContext rules,
        MethodDescriptor method,
        String name
    ) {
        String parameters = String.join(", ", methodParameters(method, rules));
        items.putIfAbsent(method.signatureKey(), CompletionItem.methodReference(name, parameters,
            method.returnType().displayName(), Modifier.isStatic(method.modifiers())));
    }

    private List<EnclosingType> enclosingTypes(SemanticModel model, JavaRuleContext rules, SyntaxNode usage) {
        List<EnclosingType> enclosingTypes = new ArrayList<>();
        boolean staticOnly = false;
        for (SyntaxNode node = usage; node != null; node = node.parent().orElse(null)) {
            ActivityCheckpoint.poll(activityCheck);
            if (node.kind().equals(JavaSyntaxKinds.STATIC_INITIALIZER)
                || node.kind().equals(JavaSyntaxKinds.METHOD_DECLARATION) && hasStaticModifier(node)
                || node.kind().equals(JavaSyntaxKinds.FIELD_DECLARATION)
                    && (hasStaticModifier(node) || isInterfaceMember(node))) {
                staticOnly = true;
            }

            Symbol symbol = model.declaredSymbol(node).orElse(null);
            if (symbol == null || !JavaTypeLookup.isTypeSymbol(symbol.kind()))
                continue;

            enclosingTypes.add(new EnclosingType(symbol.qualifiedName().orElse(symbol.simpleName()), staticOnly));
            staticOnly |= Modifier.isStatic(rules.symbolModifiers(symbol))
                || symbol.kind() != JavaSymbolKind.CLASS || isInterfaceMember(node);
        }

        return enclosingTypes;
    }

    private List<CompletionItem> declaredMethods(EnclosingType owner, JavaRuleContext rules, String prefix) {
        Map<String, CompletionItem> items = new LinkedHashMap<>();
        for (MethodDescriptor method : rules.declaredMethodDescriptors(owner.qualifiedName())) {
            ActivityCheckpoint.poll(activityCheck);
            boolean isStatic = Modifier.isStatic(method.modifiers());
            if (!NameMatcher.matches(prefix, method.name()) || owner.staticOnly() && !isStatic)
                continue;

            addMethodItem(items, method.name(), methodParameters(method, rules), method.returnType().displayName(),
                isStatic);
        }

        return items.values().stream()
            .sorted(Comparator.comparing(CompletionItem::insertText, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CompletionItem::suffix))
            .toList();
    }

    private List<String> methodParameters(MethodDescriptor method, JavaRuleContext rules) {
        List<String> parameters = new ArrayList<>(method.parameterTypes().stream().map(Type::displayName).toList());
        if (rules.isVariableArityMethod(method) && !parameters.isEmpty()
            && method.parameterTypes().getLast() instanceof JavaTypes.ArrayType array) {
            parameters.set(parameters.size() - 1, array.componentType().displayName() + "...");
        }

        return parameters;
    }

    private Map<String, FieldDescriptor> visibleFields(
        List<EnclosingType> enclosingTypes,
        JavaRuleContext rules,
        SyntaxNode usage,
        String prefix,
        Set<String> localNames
    ) {
        Map<String, FieldDescriptor> fields = new LinkedHashMap<>();
        Set<String> shadowed = new HashSet<>(localNames);
        for (EnclosingType owner : enclosingTypes) {
            for (FieldDescriptor field : rules.declaredFieldDescriptors(owner.qualifiedName())) {
                addVisibleField(fields, shadowed, field, rules, usage, prefix, owner.staticOnly());
            }

            for (FieldDescriptor field : rules.inheritedFieldDescriptors(owner.qualifiedName())) {
                if (!Modifier.isPrivate(field.modifiers())) {
                    addVisibleField(fields, shadowed, field, rules, usage, prefix, owner.staticOnly());
                }
            }
        }

        List<JavaImports.ImportDeclaration> imports = JavaImports.imports(rules.syntaxTree().root()).stream()
            .filter(JavaImports.ImportDeclaration::isStatic)
            .sorted(Comparator.comparing(JavaImports.ImportDeclaration::isWildcard))
            .toList();
        for (JavaImports.ImportDeclaration entry : imports) {
            ActivityCheckpoint.poll(activityCheck);
            if (!entry.isWildcard() && !NameMatcher.matches(prefix, entry.importedName()))
                continue;

            String owner = rules.resolveQualifiedTypeName(entry.owner(), entry.node());
            if (owner == null)
                continue;

            List<FieldDescriptor> imported = new ArrayList<>(rules.fieldDescriptorsForType(owner));
            imported.addAll(rules.inheritedFieldDescriptors(owner));
            for (FieldDescriptor field : imported) {
                if (Modifier.isStatic(field.modifiers())
                    && (entry.isWildcard() || entry.importedName().equals(field.name()))) {
                    addVisibleField(fields, shadowed, field, rules, usage, prefix, true);
                }
            }
        }

        return fields;
    }

    private void addVisibleField(
        Map<String, FieldDescriptor> fields,
        Set<String> shadowed,
        FieldDescriptor field,
        JavaRuleContext rules,
        SyntaxNode usage,
        String prefix,
        boolean staticOnly
    ) {
        ActivityCheckpoint.poll(activityCheck);
        if (NameMatcher.matches(prefix, field.name()) && !shadowed.contains(field.name())
            && rules.isFieldAccessible(field, usage)) {
            shadowed.add(field.name());
            if (!staticOnly || Modifier.isStatic(field.modifiers())) {
                fields.put(field.name(), field);
            }
        }
    }

    private static boolean hasStaticModifier(SyntaxNode node) {
        return node.children().stream()
            .anyMatch(child -> child.kind().equals(JavaSyntaxKinds.tokenKind(JavaTokenType.STATIC_KEYWORD)));
    }

    private static boolean isInterfaceMember(SyntaxNode node) {
        return node.parent().map(parent -> parent.kind().equals(JavaSyntaxKinds.INTERFACE_BODY)
            || parent.kind().equals(JavaSyntaxKinds.ANNOTATION_TYPE_BODY)).orElse(false);
    }

    private static @Nullable CompletionResult merge(
        @Nullable CompletionResult first,
        @Nullable CompletionResult second
    ) {
        if (first == null)
            return second;

        if (second == null || first.replacementStart() != second.replacementStart())
            return first;

        List<CompletionItem> items = new ArrayList<>(first.items());
        items.addAll(second.items());
        return new CompletionResult(first.replacementStart(), items);
    }

    private @Nullable SyntaxNode findMemberAccess(@Nullable SyntaxNode node) {
        for (SyntaxNode current = node; current != null; current = current.parent().orElse(null)) {
            ActivityCheckpoint.poll(activityCheck);
            if (JavaSemanticAnalyzer.explicitReceiver(current) != null)
                return current;
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
            if (JavaTypeLookup.isTypeSymbol(resolved.kind()))
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
                return completionTarget(lookup.resolveQualifiedTypeName(displayName, usageSite, model, projectIndex),
                    false);
        }

        String canonical = JavaSemanticAnalyzer.canonicalQualifiedName(receiver);
        if (canonical != null) {
            String qualified = lookup.resolveQualifiedTypeName(canonical, usageSite, model, projectIndex);
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

        collectLocalMembers(model.syntaxTree().root(), model, ownerQualifiedName, out);
        ClassStub binary = lookup.lookupBinaryClassStub(ownerQualifiedName, projectIndex);
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
        SyntaxNode node,
        SemanticModel model,
        String ownerQualifiedName,
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
                    default -> {
                    }
                }
            }
        }

        for (SyntaxNode child : node.children()) {
            ActivityCheckpoint.poll(activityCheck);
            collectLocalMembers(child, model, ownerQualifiedName, out);
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
                        TypeReference returnType = symbol.structuredSignature() == null
                            ? null
                            : symbol.structuredSignature().returnType();
                        addMethodItem(
                            out,
                            symbol.simpleName(),
                            parameterDisplaysFromSignature(symbol),
                            returnType == null ? "?" : returnType.displayName(),
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
            if (declared != null && JavaTypeLookup.isTypeSymbol(declared.kind()))
                return declared;
        }

        return null;
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

        ClassStub stub = lookup.lookupBinaryClassStub(ownerQualifiedName, projectIndex);
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
        SourceTypeDeclaration declaration = lookup.sourceTypeDeclaration(ownerQualifiedName, model, projectIndex);
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
            String qualifiedName = lookup.resolveQualifiedTypeName(
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

    private record EnclosingType(String qualifiedName, boolean staticOnly) {
    }

    private record CompletionTarget(String ownerQualifiedName, boolean staticContext) {
    }
}
