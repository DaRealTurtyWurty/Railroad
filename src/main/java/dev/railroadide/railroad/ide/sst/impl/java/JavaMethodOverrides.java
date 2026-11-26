package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.classparser.stub.MethodStub;
import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import dev.railroadide.railroad.ide.diagnostics.rules.java.JavaSemanticRules;
import dev.railroadide.railroad.ide.sst.impl.java.JavaTypeLookup.SourceTypeDeclaration;
import dev.railroadide.railroad.ide.sst.java.api.JavaSymbolKind;
import dev.railroadide.railroad.ide.sst.java.api.JavaTypes;
import dev.railroadide.railroad.ide.sst.project.JavaSymbolIndex;
import dev.railroadide.railroad.ide.sst.request.internal.ActivityCheckpoint;
import dev.railroadide.railroad.ide.sst.semantic.api.SemanticModel;
import dev.railroadide.railroad.ide.sst.semantic.api.SemanticDiagnostic;
import dev.railroadide.railroad.ide.sst.semantic.api.Symbol;
import dev.railroadide.railroad.ide.sst.semantic.api.SymbolKind;
import dev.railroadide.railroad.ide.sst.semantic.api.Type;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxToken;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxTree;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves inherited Java methods for override completion and missing implementation diagnostics and fixes.
 */
@ApiStatus.Internal
public final class JavaMethodOverrides {
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

    private final Runnable activityCheck;
    private final JavaTypeLookup lookup;
    private final String document;
    private final @Nullable JavaSymbolIndex projectIndex;
    private final Map<String, Optional<TypeInfo>> typeInfos = new HashMap<>();
    private final Map<String, RenderedName> renderedNames = new HashMap<>();
    private final Map<String, String> itemImports = new TreeMap<>();
    private SemanticModel model;
    private SyntaxNode typeDeclaration;
    private String currentPackage;
    private String owner;
    private Map<String, TypeExpr> typeErasures;
    private boolean incompleteHierarchy;

    public JavaMethodOverrides(String document, @Nullable JavaSymbolIndex projectIndex, Runnable activityCheck) {
        this.activityCheck = activityCheck instanceof ActivityCheckpoint
            ? activityCheck
            : new ActivityCheckpoint(activityCheck);
        this.lookup = new JavaTypeLookup(this.activityCheck);
        this.document = document;
        this.projectIndex = projectIndex;
    }

    private @Nullable Implementation missingImplementation(SyntaxNode declaration) {
        incompleteHierarchy = false;
        return findMissingImplementation(declaration);
    }

    /**
     * Computes override suggestions for a member-declaration position.
     *
     * @param triggerAt the first character of the typed member name, or the character before the caret when no
     *            name has been typed
     * @return override suggestions replacing the typed name, or null outside a member-declaration position
     */
    @Nullable
    public CompletionResult complete(int triggerAt) {
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
        TypeInfo current = selectType(body == null ? null : body.parent().orElse(null));
        if (current == null)
            return null;

        List<CompletionItem> items = inheritedMethods(current, declaredSignatures(current)).values().stream()
            .map(List::getFirst)
            .filter(candidate -> !candidate.method().isFinal())
            .sorted(Comparator.comparing((Candidate candidate) -> candidate.method().name(),
                String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(candidate -> candidate.method().parameters().size()))
            .map(candidate -> item(candidate, current.isInterface()))
            .toList();
        return items.isEmpty() ? null : new CompletionResult(prefixStart, items);
    }

    private @Nullable TypeInfo selectType(@Nullable SyntaxNode declaration) {
        Symbol type = declaration == null ? null : model.declaredSymbol(declaration).orElse(null);
        owner = type == null ? null : type.qualifiedName().orElse(null);
        if (owner == null || type.kind() == JavaSymbolKind.ANNOTATION)
            return null;

        typeDeclaration = declaration;
        currentPackage = lookup.currentPackageName(model.syntaxTree().root());
        TypeInfo current = sourceTypeInfo(new SourceTypeDeclaration(model, declaration, type.kind()));
        typeErasures = typeBounds(current.typeParameters(), Map.of());
        return current;
    }

    private Set<String> declaredSignatures(TypeInfo current) {
        Set<String> declared = new HashSet<>();
        for (Method method : current.methods()) {
            declared.add(method.erasedSignature(Map.of(), typeErasures));
        }

        return declared;
    }

    private Map<String, List<Candidate>> inheritedMethods(TypeInfo current, Set<String> declared) {
        Map<String, List<Candidate>> candidates = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>(Set.of(owner));
        for (TypeExpr superType : current.superTypes()) {
            if (superType instanceof TypeExpr.Named named) {
                collect(named, true, declared, candidates, visited);
            }
        }

        return candidates;
    }

    private @Nullable SyntaxNode memberPositionBody(SyntaxNode root, int prefixStart) {
        if (prefixStart < document.length() && Character.isJavaIdentifierStart(document.charAt(prefixStart)))
            return typedMemberBody(root, prefixStart);

        if (prefixStart == 0)
            return null;

        SyntaxNode node = lookup.findDeepestNodeContaining(root, prefixStart - 1);
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
        SyntaxNode node = lookup.findDeepestNodeContaining(root, prefixStart);
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
        Map<String, List<Candidate>> candidates,
        Set<String> visited
    ) {
        ActivityCheckpoint.poll(activityCheck);
        if (!visited.add(reference.name()))
            return;

        TypeInfo info = typeInfo(reference.name());
        if (info == null) {
            incompleteHierarchy = true;
            return;
        }

        boolean raw = reference.arguments().isEmpty() && !info.typeParameters().isEmpty();
        Map<String, TypeExpr> bindings = raw ? Map.of() : bind(info.typeParameters(), reference.arguments());
        Map<String, TypeExpr> rawBounds = raw ? typeBounds(info.typeParameters(), Map.of()) : Map.of();
        for (Method inherited : info.methods()) {
            ActivityCheckpoint.poll(activityCheck);
            if (inherited.isStatic() || inherited.access() == Access.PRIVATE)
                continue;

            if (inherited.access() == Access.PACKAGE && !Objects.equals(info.packageName(), currentPackage))
                continue;

            Method method = raw ? inherited.erased(rawBounds) : inherited;
            String signature = method.erasedSignature(bindings, typeErasures);
            if (declared.contains(signature))
                continue;

            candidates.computeIfAbsent(signature, _ -> new ArrayList<>())
                .add(new Candidate(method, bindings, reference.name(), info.isInterface(), direct));
        }

        for (TypeExpr superType : info.superTypes()) {
            TypeExpr resolved = raw ? erase(superType, rawBounds, new HashSet<>()) : substitute(superType, bindings);
            if (resolved instanceof TypeExpr.Named named) {
                collect(named, false, declared, candidates, visited);
            }
        }
    }

    private @Nullable RequiredMethod requiredMethod(List<Candidate> methods) {
        ActivityCheckpoint.poll(activityCheck);
        if (methods.stream().noneMatch(candidate -> candidate.method().isAbstract()))
            return null;

        Candidate superclass = methods.stream().filter(candidate -> !candidate.ownerIsInterface())
            .findFirst().orElse(null);
        Access access = methods.stream().map(candidate -> candidate.method().access()).min(Comparator.naturalOrder())
            .orElseThrow();
        if (superclass != null) {
            Method method = superclass.method();
            if (method.isFinal() || !method.isAbstract() && method.access().compareTo(access) <= 0)
                return null;
        } else {
            List<Candidate> mostSpecific = methods.stream().filter(candidate -> methods.stream()
                .noneMatch(other -> other != candidate && isSubtype(
                    new TypeExpr.Named(other.ownerName(), List.of()),
                    new TypeExpr.Named(candidate.ownerName(), List.of()), new HashSet<>())))
                .toList();
            if (mostSpecific.size() == 1 && !mostSpecific.getFirst().method().isAbstract())
                return null;
        }

        return methods.stream()
            .filter(
                candidate -> methods.stream().allMatch(other -> candidate == other || canOverride(candidate, other)))
            .findFirst().map(candidate -> new RequiredMethod(candidate, access, methods.size() == 1)).orElse(null);
    }

    private boolean canOverride(Candidate candidate, Candidate other) {
        Map<String, TypeExpr> bindings = candidate.comparisonBindings();
        Map<String, TypeExpr> otherBindings = other.comparisonBindings();
        if (!candidate.method().bounds(bindings).equals(other.method().bounds(otherBindings)))
            return false;

        List<TypeExpr> parameters = candidate.method().parameters().stream()
            .map(parameter -> substitute(parameter.type(), bindings)).toList();
        List<TypeExpr> otherParameters = other.method().parameters().stream()
            .map(parameter -> substitute(parameter.type(), otherBindings)).toList();
        return parameters.equals(otherParameters) && isSubtype(
            substitute(candidate.method().returnType(), bindings),
            substitute(other.method().returnType(), otherBindings), new HashSet<>());
    }

    private boolean isSubtype(TypeExpr type, TypeExpr expected, Set<String> visited) {
        ActivityCheckpoint.poll(activityCheck);
        if (type.equals(expected))
            return true;

        if (type instanceof TypeExpr.Primitive)
            return false;

        if (expected instanceof TypeExpr.Named named && OBJECT.equals(named.name()))
            return true;

        if (type instanceof TypeExpr.Array array && expected instanceof TypeExpr.Array expectedArray)
            return isSubtype(array.component(), expectedArray.component(), visited);

        if (!(type instanceof TypeExpr.Named named) || !(expected instanceof TypeExpr.Named expectedName))
            return false;

        if (named.name().replace('$', '.').equals(expectedName.name().replace('$', '.')))
            return expectedName.arguments().isEmpty() || named.arguments().equals(expectedName.arguments());

        if (!visited.add(named.name()))
            return false;

        TypeInfo info = typeInfo(named.name());
        if (info == null)
            return false;

        Map<String, TypeExpr> bindings = bind(info.typeParameters(), named.arguments());
        return info.superTypes().stream().anyMatch(parent -> isSubtype(substitute(parent, bindings), expected,
            visited));
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

    private static Map<String, TypeExpr> typeBounds(List<TypeParameter> parameters, Map<String, TypeExpr> bindings) {
        Map<String, TypeExpr> bounds = new HashMap<>();
        for (TypeParameter parameter : parameters) {
            TypeExpr firstBound = parameter.bounds().isEmpty()
                ? new TypeExpr.Named(OBJECT, List.of())
                : parameter.bounds().getFirst();
            bounds.put(parameter.name(), substitute(firstBound, bindings));
        }

        return bounds;
    }

    private static TypeExpr erase(TypeExpr type, Map<String, TypeExpr> bounds, Set<String> visited) {
        return switch (type) {
            case TypeExpr.Primitive primitive -> primitive;
            case TypeExpr.Array array -> new TypeExpr.Array(erase(array.component(), bounds, visited));
            case TypeExpr.Named named -> new TypeExpr.Named(named.name(), List.of());
            case TypeExpr.Variable variable -> visited.add(variable.name()) && bounds.containsKey(variable.name())
                ? erase(bounds.get(variable.name()), bounds, visited)
                : new TypeExpr.Named(OBJECT, List.of());
            case TypeExpr.Wildcard _ -> new TypeExpr.Named(OBJECT, List.of());
        };
    }

    private static String erasedTypeName(TypeExpr type) {
        return switch (type) {
            case TypeExpr.Primitive primitive -> primitive.name();
            case TypeExpr.Array array -> erasedTypeName(array.component()) + "[]";
            case TypeExpr.Named named -> named.name().replace('$', '.');
            default -> throw new IllegalArgumentException("An erased type must have a concrete name");
        };
    }

    private CompletionItem item(Candidate candidate, boolean interfaceTarget) {
        itemImports.clear();
        MethodTemplate template = methodTemplate(candidate, interfaceTarget, candidate.method().access(),
            true, true);
        return CompletionItem.override(candidate.method().name(), template.parameterTypes(),
            simpleName(candidate.ownerName()), new CompletionItem.Template(template.text(), template.caretOffset(),
                importInsertions(List.copyOf(itemImports.values()))));
    }

    private MethodTemplate methodTemplate(
        Candidate candidate,
        boolean interfaceTarget,
        Access access,
        boolean includeThrows,
        boolean delegate
    ) {
        ActivityCheckpoint.poll(activityCheck);
        Method method = candidate.method();
        Map<String, TypeExpr> bindings = candidate.signatureBindings();

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

        StringBuilder header = new StringBuilder("@").append(renderName("java.lang.Override")).append('\n');
        if (interfaceTarget) {
            header.append("default ");
        } else if (access != Access.PACKAGE) {
            header.append(access.keyword()).append(' ');
        }

        if (!method.typeParameters().isEmpty()) {
            header.append(method.typeParameters().stream()
                .map(parameter -> renderTypeParameter(parameter, bindings))
                .collect(Collectors.joining(", ", "<", "> ")));
        }

        TypeExpr returnType = substitute(method.returnType(), bindings);
        header.append(render(returnType)).append(' ').append(method.name())
            .append('(').append(String.join(", ", declarations)).append(')');
        if (includeThrows && !method.thrownTypes().isEmpty()) {
            header.append(method.thrownTypes().stream()
                .map(thrown -> render(substitute(thrown, bindings)))
                .collect(Collectors.joining(", ", " throws ", "")));
        }

        header.append(" {\n\t");
        int caretOffset = header.length();
        String text = header + bodyStatement(candidate, returnType, names, delegate) + "\n}";
        return new MethodTemplate(text, caretOffset, String.join(", ", parameterTypes));
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
                int start = JavaImports.afterTrailingComment(document, imports.getLast().end());
                insertions.merge(start, "\n" + lines, String::concat);
            } else {
                SyntaxNode packageDeclaration = JavaSemanticAnalyzer.directChild(root,
                    JavaSyntaxKinds.PACKAGE_DECLARATION.id());
                if (packageDeclaration != null) {
                    insertions.put(JavaImports.afterTrailingComment(document, packageDeclaration.end()),
                        "\n\n" + lines);
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

    private String bodyStatement(
        Candidate candidate,
        TypeExpr returnType,
        List<String> argumentNames,
        boolean delegate
    ) {
        boolean returnsVoid = returnType instanceof TypeExpr.Primitive(String name) && "void".equals(name);
        String receiver = null;
        if (delegate && !candidate.method().isAbstract()) {
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
            resolved[index] = lookup.resolveQualifiedTypeName(segments[index], typeDeclaration, model, projectIndex);
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
        SourceTypeDeclaration local = lookup.sourceTypeDeclarationInModel(qualifiedName, model);
        ClassStub stub = local == null ? lookup.lookupBinaryClassStub(qualifiedName, projectIndex) : null;
        if (stub != null) {
            info = binaryTypeInfo(stub);
        } else {
            SourceTypeDeclaration declaration = local != null
                ? local
                : lookup.sourceTypeDeclaration(qualifiedName, model, projectIndex);
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
                method.thrownTypes().stream().map(JavaMethodOverrides::fromBinary).toList(),
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
                parameter.bounds().stream().map(JavaMethodOverrides::fromBinary).toList()))
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
                    classType.typeArguments().stream().map(JavaMethodOverrides::fromBinary).toList());
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
            if (isTypeBody(child)) {
                methods.addAll(sourceMethods(child, variables, isInterface, declarationModel));
            }
        }

        return new TypeInfo(typeParameters, List.copyOf(superTypes), List.copyOf(methods), isInterface,
            lookup.currentPackageName(declarationModel.syntaxTree().root()));
    }

    private List<Method> sourceMethods(
        SyntaxNode body,
        Set<String> variables,
        boolean isInterface,
        SemanticModel declarationModel
    ) {
        List<Method> methods = new ArrayList<>();
        for (SyntaxNode member : body.children()) {
            ActivityCheckpoint.poll(activityCheck);
            if (JavaSyntaxKinds.METHOD_DECLARATION.id().equals(member.kind().id())) {
                Method method = sourceMethod(member, variables, isInterface, declarationModel);
                if (method != null) {
                    methods.add(method);
                }
            }
        }

        return methods;
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
            : withTrailingDimensions(fromSource(returnReference, variables, declarationModel), declaration);

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

                TypeExpr type = withTrailingDimensions(fromSource(typeReference, variables, declarationModel),
                    parameter);
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

    private static TypeExpr withTrailingDimensions(TypeExpr type, SyntaxNode declaration) {
        for (SyntaxNode child : declaration.children()) {
            if (JavaSyntaxKinds.ARRAY_DIMENSION.id().equals(child.kind().id())) {
                type = new TypeExpr.Array(type);
            }
        }

        return type;
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
                SyntaxToken name = firstIdentifier(child);
                if (name != null) {
                    variables.add(name.text());
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
                .add(new TypeParameter(Objects.requireNonNull(firstIdentifier(parameter)).text(), List.copyOf(bounds)));
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

        String qualified = lookup.resolveQualifiedTypeName(text, node, declarationModel, projectIndex);
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

    private static @Nullable SyntaxToken firstIdentifier(SyntaxNode node) {
        for (SyntaxNode child : node.children()) {
            if (child instanceof SyntaxToken token && JavaSemanticAnalyzer.isIdentifierLikeToken(token))
                return token;
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
        private String erasedSignature(Map<String, TypeExpr> bindings, Map<String, TypeExpr> typeErasures) {
            Map<String, TypeExpr> signatureBindings = withoutMethodVariables(bindings);
            Map<String, TypeExpr> erasures = new HashMap<>(typeErasures);
            erasures.putAll(typeBounds(typeParameters, signatureBindings));

            return parameters.stream()
                .map(parameter -> erasedTypeName(erase(substitute(parameter.type(), signatureBindings), erasures,
                    new HashSet<>())))
                .collect(Collectors.joining(",", name + "(", ")"));
        }

        private Method erased(Map<String, TypeExpr> classBounds) {
            Map<String, TypeExpr> bounds = new HashMap<>(classBounds);
            bounds.putAll(typeBounds(typeParameters, Map.of()));
            return new Method(name, List.of(), erase(returnType, bounds, new HashSet<>()), parameters.stream()
                .map(parameter -> new Parameter(parameter.name(), erase(parameter.type(), bounds, new HashSet<>()),
                    parameter.varargs()))
                .toList(),
                thrownTypes.stream().map(type -> erase(type, bounds, new HashSet<>())).toList(),
                access, isStatic, isFinal, isAbstract);
        }

        private Map<String, TypeExpr> withoutMethodVariables(Map<String, TypeExpr> bindings) {
            if (typeParameters.isEmpty())
                return bindings;

            Map<String, TypeExpr> filtered = new HashMap<>(bindings);
            typeParameters.forEach(parameter -> filtered.remove(parameter.name()));
            return filtered;
        }

        private List<List<TypeExpr>> bounds(Map<String, TypeExpr> bindings) {
            return typeParameters.stream().map(parameter -> parameter.bounds().stream()
                .map(bound -> substitute(bound, bindings))
                .filter(bound -> !bound.equals(new TypeExpr.Named(OBJECT, List.of())))
                .toList()).toList();
        }
    }

    private record MethodTemplate(String text, int caretOffset, String parameterTypes) {
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
        private Map<String, TypeExpr> signatureBindings() {
            return method.withoutMethodVariables(bindings);
        }

        private Map<String, TypeExpr> comparisonBindings() {
            Map<String, TypeExpr> result = new HashMap<>(bindings);
            for (int index = 0; index < method.typeParameters().size(); index++) {
                result.put(method.typeParameters().get(index).name(), new TypeExpr.Variable("#" + index));
            }

            return result;
        }
    }

    private record RequiredMethod(Candidate candidate, Access access, boolean includeThrows) {
    }

    public static @Nullable SyntaxNode implementationBody(SyntaxNode declaration) {
        String bodyKind = switch (declaration.kind().id()) {
            case "JAVA_CLASS_DECLARATION" -> JavaSyntaxKinds.CLASS_BODY.id();
            case "JAVA_ENUM_DECLARATION" -> JavaSyntaxKinds.ENUM_BODY.id();
            case "JAVA_RECORD_DECLARATION" -> JavaSyntaxKinds.RECORD_BODY.id();
            case "JAVA_CLASS_INSTANCE_CREATION_EXPRESSION" -> JavaSyntaxKinds.ANONYMOUS_CLASS_BODY.id();
            default -> null;
        };

        if (bodyKind == null || JavaSemanticAnalyzer.directChild(declaration,
            tokenKindId(JavaTokenType.ABSTRACT_KEYWORD)) != null)
            return null;

        if (!JavaSyntaxKinds.ANONYMOUS_CLASS_BODY.id().equals(bodyKind)
            && JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.EXTENDS_CLAUSE.id()) == null
            && JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.IMPLEMENTS_CLAUSE.id()) == null)
            return null;

        SyntaxNode body = JavaSemanticAnalyzer.directChild(declaration, bodyKind);
        return body != null && JavaSemanticAnalyzer.directChild(body,
            tokenKindId(JavaTokenType.OPEN_BRACE)) != null && JavaSemanticAnalyzer.directChild(body,
                tokenKindId(JavaTokenType.CLOSE_BRACE)) != null ? body : null;
    }

    public static @Nullable Implementation missingImplementation(
        SyntaxTree tree,
        SyntaxNode declaration,
        JavaSymbolIndex projectIndex,
        Runnable activityCheck
    ) {
        var overrides = new JavaMethodOverrides(tree.documentSnapshot().text(), projectIndex, activityCheck);
        overrides.model = anonymousDiamond(declaration)
            ? JavaSemanticAnalyzer.analyzeFacts(tree, projectIndex,
                activityCheck)
            : JavaSemanticAnalyzer.analyzeDeclarationsFacts(tree, projectIndex, activityCheck);
        return overrides.missingImplementation(declaration);
    }

    /**
     * Reports unimplemented inherited methods on the names of concrete types in the captured document.
     *
     * @param model semantic facts for the document
     * @param projectIndex visible project symbols, or null for the runtime library
     * @param activityCheck cooperative request activity check
     * @return missing implementation errors
     */
    public static List<SemanticDiagnostic> diagnostics(
        SemanticModel model,
        @Nullable JavaSymbolIndex projectIndex,
        Runnable activityCheck
    ) {
        var overrides = new JavaMethodOverrides(model.syntaxTree().documentSnapshot().text(), projectIndex,
            activityCheck);
        overrides.model = model;
        List<SemanticDiagnostic> diagnostics = new ArrayList<>();
        overrides.collectDiagnostics(model.syntaxTree().root(), diagnostics);
        activityCheck.run();
        return List.copyOf(diagnostics);
    }

    private void collectDiagnostics(SyntaxNode node, List<SemanticDiagnostic> diagnostics) {
        ActivityCheckpoint.poll(activityCheck);
        SyntaxNode body = implementationBody(node);
        if (body != null) {
            SyntaxNode name = JavaSyntaxKinds.ANONYMOUS_CLASS_BODY.id().equals(body.kind().id())
                ? JavaSemanticAnalyzer.directChild(node, JavaSyntaxKinds.TYPE_REFERENCE.id())
                : firstIdentifier(node);
            Implementation missing = name == null ? null : missingImplementation(node);
            if (missing != null) {
                var rule = JavaSemanticRules.MISSING_METHOD_IMPLEMENTATIONS;
                String methods = missing.methods.stream().map(required -> required.candidate().method().name())
                    .distinct().collect(Collectors.joining(", "));
                diagnostics.add(new SemanticDiagnostic(rule.defaultSeverity(), rule.id(),
                    rule.format(document.substring(name.start(), name.end()).strip(), methods),
                    name.start(), name.end(), node));
            }
        }

        for (SyntaxNode child : node.children()) {
            if (!(child instanceof SyntaxToken)) {
                collectDiagnostics(child, diagnostics);
            }
        }
    }

    public static final class Implementation {
        private final JavaMethodOverrides overrides;
        private final List<RequiredMethod> methods;

        private Implementation(JavaMethodOverrides overrides, List<RequiredMethod> methods) {
            this.overrides = overrides;
            this.methods = methods;
        }

        public Stubs generate() {
            overrides.itemImports.clear();
            List<String> declarations = methods.stream()
                .map(required -> overrides.methodTemplate(required.candidate(), false,
                    required.access(), required.includeThrows(), false).text())
                .toList();
            return new Stubs(declarations, overrides.importInsertions(List.copyOf(overrides.itemImports.values())));
        }
    }

    public record Stubs(List<String> declarations, List<CompletionItem.Insertion> imports) {
    }

    private @Nullable Implementation findMissingImplementation(SyntaxNode declaration) {
        SyntaxNode body = implementationBody(declaration);
        if (body == null)
            return null;

        TypeInfo current = JavaSyntaxKinds.ANONYMOUS_CLASS_BODY.id().equals(body.kind().id())
            ? selectAnonymousType(declaration, body)
            : selectType(declaration);
        if (current == null)
            return null;

        Set<String> declared = declaredSignatures(current);
        if (JavaSyntaxKinds.RECORD_DECLARATION.id().equals(declaration.kind().id())) {
            declared.addAll(Set.of("equals(java.lang.Object)", "hashCode()", "toString()"));
            SyntaxNode header = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.RECORD_HEADER.id());
            if (header != null) {
                for (SyntaxNode component : header.children()) {
                    if (JavaSyntaxKinds.RECORD_COMPONENT.id().equals(component.kind().id())) {
                        SyntaxToken name = firstIdentifier(component);
                        if (name != null) {
                            declared.add(name.text() + "()");
                        }
                    }
                }
            }
        }

        if (JavaSyntaxKinds.ENUM_DECLARATION.id().equals(declaration.kind().id())) {
            declared.addAll(enumConstantImplementations(body));
        }

        Map<String, List<Candidate>> inherited = inheritedMethods(current, declared);
        if (incompleteHierarchy)
            return null;

        List<RequiredMethod> methods = inherited.values().stream()
            .map(this::requiredMethod)
            .filter(Objects::nonNull)
            .toList();
        return methods.isEmpty() ? null : new Implementation(this, methods);
    }

    private @Nullable TypeInfo selectAnonymousType(SyntaxNode declaration, SyntaxNode body) {
        SyntaxNode reference = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.TYPE_REFERENCE.id());
        Symbol symbol = model.declaredSymbol(body).orElse(null);
        if (reference == null || symbol == null)
            return null;

        owner = symbol.qualifiedName().orElseThrow();
        typeDeclaration = body;
        currentPackage = lookup.currentPackageName(model.syntaxTree().root());
        Map<String, TypeExpr> variables = new LinkedHashMap<>();
        for (SyntaxNode enclosing = declaration.parent().orElse(null); enclosing != null; enclosing = enclosing.parent()
            .orElse(null)) {
            ActivityCheckpoint.poll(activityCheck);
            typeBounds(sourceTypeParameters(enclosing, Set.of(), model), Map.of()).forEach(variables::putIfAbsent);
        }

        typeErasures = variables;
        TypeExpr superType = fromSource(reference, variables.keySet(), model);
        if (!(superType instanceof TypeExpr.Named named))
            return null;

        TypeInfo info = typeInfo(named.name());
        if (info == null)
            return null;

        if (anonymousDiamond(declaration)) {
            Type inferred = model.inferredType(declaration).orElse(null);
            if (!(inferred instanceof JavaTypes.DeclaredType declared)
                || declared.typeArguments().size() != info.typeParameters().size())
                return null;

            List<TypeExpr> arguments = declared.typeArguments().stream()
                .map(argument -> fromInferredType(argument, variables.keySet())).toList();
            if (arguments.contains(null))
                return null;

            named = new TypeExpr.Named(named.name(), arguments);
        }

        List<TypeExpr> ancestors = info.isInterface()
            ? List.of(new TypeExpr.Named(OBJECT, List.of()), named)
            : List.of(named);
        return new TypeInfo(List.of(), ancestors, sourceMethods(body, variables.keySet(), false, model), false,
            currentPackage);
    }

    private static boolean anonymousDiamond(SyntaxNode declaration) {
        if (!JavaSyntaxKinds.CLASS_INSTANCE_CREATION_EXPRESSION.id().equals(declaration.kind().id()))
            return false;

        SyntaxNode reference = JavaSemanticAnalyzer.directChild(declaration, JavaSyntaxKinds.TYPE_REFERENCE.id());
        return reference != null
            && JavaSemanticAnalyzer.directChild(reference, JavaSyntaxKinds.DIAMOND_TYPE_ARGUMENTS.id()) != null;
    }

    private @Nullable TypeExpr fromInferredType(Type type, Set<String> variables) {
        return switch (type) {
            case JavaTypes.TypeVariableType variable -> variables.contains(variable.displayName())
                ? new TypeExpr.Variable(variable.displayName())
                : null;
            case JavaTypes.ArrayType array -> {
                TypeExpr component = fromInferredType(array.componentType(), variables);
                yield component == null ? null : new TypeExpr.Array(component);
            }

            case JavaTypes.PrimitiveType primitive -> new TypeExpr.Primitive(primitive.displayName());
            case JavaTypes.DeclaredType declared -> {
                List<TypeExpr> arguments = declared.typeArguments().stream()
                    .map(argument -> fromInferredType(argument, variables)).toList();
                String name = lookup.resolveQualifiedTypeName(declared.displayName(), typeDeclaration, model,
                    projectIndex);
                yield name == null || arguments.contains(null) ? null : new TypeExpr.Named(name, arguments);
            }

            default -> null;
        };
    }

    private Set<String> enumConstantImplementations(SyntaxNode enumBody) {
        Set<String> shared = null;
        for (SyntaxNode child : enumBody.children()) {
            if (!JavaSyntaxKinds.ENUM_CONSTANT.id().equals(child.kind().id()))
                continue;

            SyntaxNode body = JavaSemanticAnalyzer.directChild(child, JavaSyntaxKinds.ANONYMOUS_CLASS_BODY.id());
            if (body == null)
                return Set.of();

            Set<String> signatures = new HashSet<>();
            for (Method method : sourceMethods(body, Set.of(), false, model)) {
                if (!method.isAbstract() && !method.isStatic() && method.access() != Access.PRIVATE) {
                    signatures.add(method.erasedSignature(Map.of(), Map.of()));
                }
            }

            if (shared == null) {
                shared = signatures;
            } else {
                shared.retainAll(signatures);
            }
        }

        return shared == null ? Set.of() : shared;
    }
}
