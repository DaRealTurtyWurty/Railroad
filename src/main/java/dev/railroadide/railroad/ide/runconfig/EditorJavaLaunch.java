package dev.railroadide.railroad.ide.runconfig;

import dev.railroadide.railroad.ide.runconfig.defaults.data.GradleRunConfigurationData;
import dev.railroadide.railroad.ide.runconfig.defaults.data.JavaApplicationRunConfigurationData;
import dev.railroadide.railroad.ide.sst.document.api.LineMap;
import dev.railroadide.railroad.ide.sst.impl.java.*;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxKind;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxToken;
import dev.railroadide.railroad.java.JDK;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.project.facet.FacetManager;
import dev.railroadide.railroad.utility.ProjectPathIdentityUtils;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static dev.railroadide.railroad.ide.sst.impl.java.JavaSemanticAnalyzer.*;
import static dev.railroadide.railroad.ide.sst.impl.java.JavaSyntaxKinds.*;

/**
 * A Java main or test method that can be launched from the editor.
 *
 * @param line one-based line of the method's name
 * @param className binary name of the class declaring the method
 * @param testMethod test method selector, or null for a main launch
 */
public record EditorJavaLaunch(int line, String className, @Nullable String testMethod) {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("org.junit.Test", "org.junit.jupiter.api.Test",
        "org.junit.jupiter.params.ParameterizedTest", "org.junit.jupiter.api.RepeatedTest",
        "org.junit.jupiter.api.TestTemplate", "org.junit.jupiter.api.TestFactory", "org.testng.annotations.Test");

    private static final Map<SyntaxKind, JavaTokenType> TYPE_KEYWORDS = Map.of(
        CLASS_DECLARATION, JavaTokenType.CLASS_KEYWORD, INTERFACE_DECLARATION, JavaTokenType.INTERFACE_KEYWORD,
        ENUM_DECLARATION, JavaTokenType.ENUM_KEYWORD, RECORD_DECLARATION, JavaTokenType.RECORD_KEYWORD,
        ANNOTATION_TYPE_DECLARATION, JavaTokenType.AT_INTERFACE_KEYWORD);
    private static final Set<SyntaxKind> TYPE_BODIES = Set.of(CLASS_BODY, INTERFACE_BODY, ENUM_BODY, RECORD_BODY,
        ANNOTATION_TYPE_BODY);

    public String displayName() {
        return className + "." + (testMethod == null ? "main" : testMethod) + "()";
    }

    /**
     * Finds the launchable main and annotated test methods declared in a Java source file.
     *
     * @param source Java source text
     * @param filename name of the source file, used to parse it as a compilation unit
     * @return launchable declarations in source order
     */
    public static List<EditorJavaLaunch> find(String source, String filename) {
        var tree = JavaSyntaxParser.parse(source);
        SyntaxNode unit = tree.root();
        SyntaxNode packageDeclaration = directChild(unit, PACKAGE_DECLARATION.id());
        String packageName = packageDeclaration == null ? "" : qualifiedName(packageDeclaration);
        String prefix = packageName.isEmpty() ? "" : packageName + ".";
        boolean compact = unit.children().stream().anyMatch(child -> child.kind().equals(METHOD_DECLARATION)
            || child.kind().equals(FIELD_DECLARATION));
        String implicitClass = compact ? prefix + filename.replaceFirst("\\.java$", "") : null;

        Set<String> annotations = new HashSet<>(TEST_ANNOTATIONS);
        List<JavaImports.ImportDeclaration> imports = JavaImports.imports(unit);
        for (String annotation : TEST_ANNOTATIONS) {
            int dot = annotation.lastIndexOf('.');
            String owner = annotation.substring(0, dot);
            String simple = annotation.substring(dot + 1);
            Optional<JavaImports.ImportDeclaration> explicit = imports.stream()
                .filter(item -> !item.isStatic() && item.importedName().equals(simple))
                .findFirst();
            if (explicit.isPresent()
                ? explicit.get().target().equals(annotation)
                : owner.equals(packageName) || imports.stream().anyMatch(item -> !item.isStatic()
                    && item.isWildcard() && item.owner().equals(owner))) {
                annotations.add(simple);
            }
        }

        List<EditorJavaLaunch> targets = new ArrayList<>();
        collect(unit, prefix, implicitClass, annotations, tree.documentSnapshot().lineMap(), targets);
        return List.copyOf(targets);
    }

    private static String qualifiedName(SyntaxNode declaration) {
        SyntaxNode name = directChild(declaration, QUALIFIED_NAME.id());
        return name == null ? "" : Objects.toString(canonicalQualifiedName(name), "");
    }

    private static void collect(
        SyntaxNode body,
        String prefix,
        @Nullable String owner,
        Set<String> annotations,
        LineMap lines,
        List<EditorJavaLaunch> targets
    ) {
        for (SyntaxNode member : body.children()) {
            JavaTokenType keyword = TYPE_KEYWORDS.get(member.kind());
            if (keyword != null) {
                String name = identifierAfterKeyword(member, keyword);
                if (name != null) {
                    String binaryName = owner == null ? prefix + name : owner + "$" + name;
                    for (SyntaxNode part : member.children()) {
                        if (TYPE_BODIES.contains(part.kind())) {
                            collect(part, prefix, binaryName, annotations, lines, targets);
                        }
                    }
                }
            } else if (member.kind().equals(METHOD_DECLARATION) && owner != null) {
                SyntaxToken name = identifierTokenBeforeChildKind(member, PARAMETER_LIST.id());
                if (name == null)
                    continue;

                String method = JavaUnicodeEscapes.translate(name.text());
                int line = lines.positionAt(name.start()).line() + 1;
                if (method.equals("main") && isMainSignature(member)) {
                    targets.add(new EditorJavaLaunch(line, owner, null));
                }

                if (member.children().stream().anyMatch(child -> child.kind().equals(ANNOTATION)
                    && annotations.contains(qualifiedName(child)))) {
                    targets.add(new EditorJavaLaunch(line, owner, method));
                }
            }
        }
    }

    private static boolean isMainSignature(SyntaxNode method) {
        SyntaxNode result = directChild(method, TYPE_REFERENCE.id());
        SyntaxNode parameters = directChild(method, PARAMETER_LIST.id());
        if (result == null || !"void".equals(canonicalTypeText(result)) || parameters == null
            || directChild(method, tokenKind(JavaTokenType.PRIVATE_KEYWORD).id()) != null
            || directChild(method, tokenKind(JavaTokenType.ABSTRACT_KEYWORD).id()) != null)
            return false;

        List<SyntaxNode> arguments = parameters.children().stream().filter(child -> child.kind().equals(PARAMETER))
            .toList();
        if (arguments.isEmpty())
            return true;

        if (arguments.size() != 1)
            return false;

        SyntaxNode argument = arguments.getFirst();
        SyntaxNode type = directChild(argument, TYPE_REFERENCE.id());
        if (type == null)
            return false;

        String text = canonicalTypeText(type);
        long dimensions = argument.children().stream().filter(child -> child.kind().equals(ARRAY_DIMENSION)
            || child.kind().equals(tokenKind(JavaTokenType.ELLIPSIS))).count();
        return dimensions == 0 && ("String[]".equals(text) || "java.lang.String[]".equals(text))
            || dimensions == 1 && ("String".equals(text) || "java.lang.String".equals(text));
    }

    /**
     * Reports whether the file supports editor launch configurations.
     *
     * @param project project containing the file
     * @param file source file
     * @return whether the file is a Gradle project source, or a main source of a plain Java project
     */
    public static boolean supports(Project project, Path file) {
        Path root = ProjectPathIdentityUtils.normalize(project.getPath());
        Path source = ProjectPathIdentityUtils.normalize(file);
        return source.startsWith(root) && (project.getGradleManager().isGradleProject()
            || (!project.hasFacet(FacetManager.MAVEN) && source.startsWith(root.resolve("src/main/java"))));
    }

    /**
     * Returns the run configuration for this method, creating and registering it on first use.
     *
     * @param project project containing the file
     * @param file source file declaring the method
     * @return existing or new run configuration
     */
    public RunConfiguration<?> configuration(Project project, Path file) {
        String source = ProjectPathIdentityUtils.normalize(project.getPath())
            .relativize(ProjectPathIdentityUtils.normalize(file)).toString().replace(File.separatorChar, '/');
        String key = testMethod == null
            ? "editor-main:" + source + ":" + className
            : "editor-test:" + source + ":" + displayName();
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        RunConfigurationManager manager = project.getRunConfigManager();
        RunConfiguration<?> existing = manager.getConfigurationByUUID(id);
        if (existing != null)
            return existing;

        RunConfiguration<?> config;
        if (project.getGradleManager().isGradleProject()) {
            var type = RunConfigurationTypes.GRADLE;
            GradleRunConfigurationData data = type.createDataInstance(project);
            data.setMainClass(className);
            data.setMainSource(source);
            data.setTestMethod(testMethod);
            JDK jdk = project.getGradleManager().getGradleSettings().getGradleJvm();
            if (jdk != null) {
                data.setJavaHome(jdk);
            }

            config = new RunConfiguration<>(id, type, data);
        } else {
            var type = RunConfigurationTypes.JAVA_APPLICATION;
            JavaApplicationRunConfigurationData data = type.createDataInstance(project);
            data.setMainClass(className);
            data.setBuildBeforeRun(true);
            data.setClasspathEntries(new String[]{".railroad/classes"});

            config = new RunConfiguration<>(id, type, data);
        }

        config.data().setName(displayName());
        manager.addConfiguration(config);
        return config;
    }
}
