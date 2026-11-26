package dev.railroadide.railroad.ide.language.impl.groovy;

import dev.railroadide.railroad.ide.language.jvm.JvmClassDeclaration;
import dev.railroadide.railroad.ide.language.jvm.JvmClassDeclaration.Member;
import dev.railroadide.railroad.ide.sst.document.api.Location.TextLocation;
import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import org.apache.groovy.util.BeanUtils;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.classgen.asm.BytecodeHelper;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes Groovy classes after semantic analysis as signature-only JVM class files: the members Java code sees when
 * it compiles against Groovy, as in a joint compilation. Property accessors are added the way Groovy adds them, as are
 * the overloads Groovy generates for parameters with default values and the default constructor. Members that later
 * AST transformations add are not described, and Groovy's {@code GroovyObject} methods are omitted.
 */
public final class GroovyClassFiles {
    private static final int CLASS_FLAGS = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE
        | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_SYNTHETIC;
    private static final int INNER_FLAGS = CLASS_FLAGS | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED
        | Opcodes.ACC_STATIC;
    private static final int FIELD_FLAGS = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED
        | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_VOLATILE | Opcodes.ACC_TRANSIENT
        | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ENUM;
    private static final int METHOD_FLAGS = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED
        | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_VARARGS
        | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_STRICT | Opcodes.ACC_SYNTHETIC;

    private GroovyClassFiles() {
    }

    /**
     * Describes one class of a compiled Groovy document.
     *
     * @param node class after semantic analysis
     * @param snapshot document revision declaring the class
     * @return the class file and the source locations of the class and its members
     */
    public static JvmClassDeclaration describe(ClassNode node, TextDocumentSnapshot snapshot) {
        var writer = new ClassWriter(0);
        String internalName = BytecodeHelper.getClassInternalName(node);
        ClassNode superClass = node.isInterface() || node.getSuperClass() == null
            ? ClassHelper.OBJECT_TYPE
            : node.getSuperClass();
        writer.visit(Opcodes.V17, classAccess(node), internalName, BytecodeHelper.getGenericsSignature(node),
            BytecodeHelper.getClassInternalName(superClass),
            Arrays.stream(node.getInterfaces()).map(BytecodeHelper::getClassInternalName).toArray(String[]::new));
        if (node.getOuterClass() != null) {
            writer.visitInnerClass(internalName, BytecodeHelper.getClassInternalName(node.getOuterClass()),
                simpleName(node), node.getModifiers() & INNER_FLAGS);
        }

        for (Iterator<InnerClassNode> inner = node.getInnerClasses(); inner.hasNext();) {
            InnerClassNode innerClass = inner.next();
            if (!innerClass.isAnonymous()) {
                writer.visitInnerClass(BytecodeHelper.getClassInternalName(innerClass), internalName,
                    simpleName(innerClass), innerClass.getModifiers() & INNER_FLAGS);
            }
        }

        TextLocation classLocation = location(node, snapshot, null);
        List<Member> members = new ArrayList<>();
        for (FieldNode field : node.getFields()) {
            writer.visitField(field.getModifiers() & FIELD_FLAGS, field.getName(),
                BytecodeHelper.getTypeDescription(field.getType()), BytecodeHelper.getGenericsBounds(field.getType()),
                null).visitEnd();
            members.add(member(field.getName(), -1, field, snapshot, classLocation));
        }

        for (PropertyNode property : node.getProperties()) {
            accessors(writer, node, property, snapshot, classLocation, members);
        }

        for (MethodNode method : node.getMethods()) {
            for (Parameter[] parameters : variants(method.getParameters())) {
                String signature = parameters.length == method.getParameters().length
                    ? BytecodeHelper.getGenericsMethodSignature(method)
                    : null;
                method(writer, method.getModifiers() & METHOD_FLAGS, method.getName(), method.getReturnType(),
                    parameters, signature, method.getExceptions());
                members.add(member(method.getName(), parameters.length, method, snapshot, classLocation));
            }
        }

        List<ConstructorNode> constructors = node.getDeclaredConstructors();
        if (constructors.isEmpty() && !node.isInterface()) {
            method(writer, node.isEnum() ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC, Member.CONSTRUCTOR,
                ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, null, ClassNode.EMPTY_ARRAY);
            members.add(member(Member.CONSTRUCTOR, 0, node, snapshot, classLocation));
        }

        for (ConstructorNode constructor : constructors) {
            for (Parameter[] parameters : variants(constructor.getParameters())) {
                String signature = parameters.length == constructor.getParameters().length
                    ? BytecodeHelper.getGenericsMethodSignature(constructor)
                    : null;
                method(writer, constructor.getModifiers() & METHOD_FLAGS, Member.CONSTRUCTOR, ClassHelper.VOID_TYPE,
                    parameters, signature, constructor.getExceptions());
                members.add(member(Member.CONSTRUCTOR, parameters.length, constructor, snapshot, classLocation));
            }
        }

        writer.visitEnd();
        return new JvmClassDeclaration(writer.toByteArray(), snapshot.uri(), classLocation,
            selection(classLocation, simpleName(node), snapshot), members);
    }

    private static void accessors(
        ClassWriter writer,
        ClassNode owner,
        PropertyNode property,
        TextDocumentSnapshot snapshot,
        @Nullable TextLocation classLocation,
        List<Member> members
    ) {
        String capitalized = BeanUtils.capitalize(property.getName());
        int access = Opcodes.ACC_PUBLIC | (property.isStatic() ? Opcodes.ACC_STATIC : 0);
        ClassNode type = property.getType();
        String bounds = BytecodeHelper.getGenericsBounds(type);
        List<String> getters = ClassHelper.isPrimitiveBoolean(type)
            ? List.of("is" + capitalized, "get" + capitalized)
            : List.of("get" + capitalized);
        for (String getter : getters) {
            if (owner.getDeclaredMethods(getter).stream().noneMatch(method -> method.getParameters().length == 0)) {
                method(writer, access, getter, type, Parameter.EMPTY_ARRAY, bounds == null ? null : "()" + bounds,
                    ClassNode.EMPTY_ARRAY);
                members.add(member(getter, 0, property, snapshot, classLocation));
            }
        }

        String setter = "set" + capitalized;
        if ((property.getModifiers() & Opcodes.ACC_FINAL) == 0
            && owner.getDeclaredMethods(setter).stream().noneMatch(method -> method.getParameters().length == 1)) {
            method(writer, access, setter, ClassHelper.VOID_TYPE, new Parameter[]{new Parameter(type,
                property.getName())}, bounds == null ? null : "(" + bounds + ")V", ClassNode.EMPTY_ARRAY);
            members.add(member(setter, 1, property, snapshot, classLocation));
        }
    }

    private static void method(
        ClassWriter writer,
        int access,
        String name,
        ClassNode returnType,
        Parameter[] parameters,
        @Nullable String signature,
        ClassNode[] exceptions
    ) {
        MethodVisitor visitor = writer.visitMethod(access, name,
            BytecodeHelper.getMethodDescriptor(returnType, parameters), signature,
            exceptions.length == 0
                ? null
                : Arrays.stream(exceptions).map(BytecodeHelper::getClassInternalName).toArray(String[]::new));
        for (Parameter parameter : parameters) {
            visitor.visitParameter(parameter.getName(), 0);
        }

        visitor.visitEnd();
    }

    /**
     * Lists the parameter lists Groovy generates for a callable: the declared list, then one overload for each
     * parameter with a default value, dropping defaulted parameters from the last onwards.
     */
    private static List<Parameter[]> variants(Parameter[] parameters) {
        List<Integer> defaulted = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].hasInitialExpression()) {
                defaulted.add(i);
            }
        }

        List<Parameter[]> variants = new ArrayList<>();
        variants.add(parameters);
        for (int dropped = 1; dropped <= defaulted.size(); dropped++) {
            List<Integer> omitted = defaulted.subList(defaulted.size() - dropped, defaulted.size());
            List<Parameter> kept = new ArrayList<>();
            for (int i = 0; i < parameters.length; i++) {
                if (!omitted.contains(i)) {
                    kept.add(parameters[i]);
                }
            }

            variants.add(kept.toArray(Parameter[]::new));
        }

        return variants;
    }

    private static int classAccess(ClassNode node) {
        int modifiers = node.getModifiers();
        int access = modifiers & CLASS_FLAGS;
        if ((modifiers & Opcodes.ACC_PROTECTED) != 0) {
            access |= Opcodes.ACC_PUBLIC;
        }

        return node.isInterface() ? access : access | Opcodes.ACC_SUPER;
    }

    private static String simpleName(ClassNode node) {
        String name = node.getNameWithoutPackage();
        return name.substring(name.lastIndexOf('$') + 1);
    }

    private static Member member(
        String name,
        int parameterCount,
        ASTNode node,
        TextDocumentSnapshot snapshot,
        @Nullable TextLocation fallback
    ) {
        TextLocation declaration = location(node, snapshot, fallback);
        if (declaration == null) {
            declaration = TextLocation.from(snapshot, 0, 0);
        }

        String sourceName = name.equals(Member.CONSTRUCTOR) ? null : sourceName(node, name);
        return new Member(name, parameterCount, declaration,
            sourceName == null ? null : selection(declaration, sourceName, snapshot));
    }

    private static @Nullable String sourceName(ASTNode node, String name) {
        if (node instanceof PropertyNode property)
            return property.getName();

        return name;
    }

    private static @Nullable TextLocation location(
        ASTNode node,
        TextDocumentSnapshot snapshot,
        @Nullable TextLocation fallback
    ) {
        if (node.getLineNumber() < 1)
            return fallback;

        String text = snapshot.text();
        int start = GroovyCompiler.offset(text, node.getLineNumber(), node.getColumnNumber());
        int end = node.getLastLineNumber() < 1
            ? start
            : GroovyCompiler.offset(text, node.getLastLineNumber(), node.getLastColumnNumber());
        return TextLocation.from(snapshot, start, Math.max(start, end));
    }

    private static @Nullable TextLocation selection(
        @Nullable TextLocation declaration,
        String name,
        TextDocumentSnapshot snapshot
    ) {
        if (declaration == null || name.isEmpty())
            return null;

        int start = declaration.range().start;
        int end = declaration.range().end;
        Matcher matcher = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])")
            .matcher(snapshot.text()).region(start, end);
        return matcher.find() ? TextLocation.from(snapshot, matcher.start(), matcher.end()) : null;
    }
}
