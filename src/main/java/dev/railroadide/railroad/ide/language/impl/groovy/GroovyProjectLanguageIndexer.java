package dev.railroadide.railroad.ide.language.impl.groovy;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.ide.language.impl.GroovyLanguageSupport;
import dev.railroadide.railroad.ide.language.index.ProjectIndexContext;
import dev.railroadide.railroad.ide.language.index.ProjectLanguageIndexer;
import dev.railroadide.railroad.ide.language.index.ProjectTypeIndex;
import dev.railroadide.railroad.ide.language.index.ProjectTypeIndex.ProjectType;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.ModuleNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the Groovy project index by parsing each source; class references are not resolved, so no classpath is
 * needed. Scripts declare no types.
 */
public final class GroovyProjectLanguageIndexer
    implements ProjectLanguageIndexer<GroovyProjectIndex, GroovyProjectIndex.FileTypes> {
    @Override
    public String languageId() {
        return GroovyLanguageSupport.LANGUAGE_ID;
    }

    @Override
    public boolean supports(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".groovy");
    }

    @Override
    public GroovyProjectIndex build(ProjectIndexContext context, Collection<Path> sourceFiles) {
        Map<Path, GroovyProjectIndex.FileTypes> files = new HashMap<>();
        for (Path file : sourceFiles.stream().filter(this::supports).sorted().toList()) {
            context.checkActive();
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException exception) {
                Railroad.LOGGER.warn("Cannot read Groovy source {} for indexing", file, exception);
                continue;
            }

            files.put(file, indexFile(context, file, text));
        }

        return new GroovyProjectIndex(files);
    }

    @Override
    public GroovyProjectIndex.FileTypes indexFile(ProjectIndexContext context, Path sourceFile, String sourceContent) {
        ModuleNode module = GroovyCompiler.parse(sourceFile.getFileName().toString(), sourceContent,
            context::checkActive);
        if (module == null)
            return new GroovyProjectIndex.FileTypes(sourceFile, List.of());

        List<ProjectType> types = new ArrayList<>();
        for (ClassNode node : module.getClasses()) {
            context.checkActive();
            if (node.isScript() || (node instanceof InnerClassNode inner && inner.isAnonymous()))
                continue;

            String name = node.getNameWithoutPackage();
            String simpleName = name.substring(name.lastIndexOf('$') + 1);
            types.add(new ProjectType(simpleName, node.getName().replace('$', '.'), kind(node), sourceFile,
                nameOffset(sourceContent, node, simpleName)));
        }

        return new GroovyProjectIndex.FileTypes(sourceFile, types);
    }

    @Override
    public GroovyProjectIndex withUpdatedFile(
        GroovyProjectIndex index,
        Path sourceFile,
        GroovyProjectIndex.FileTypes indexedFile
    ) {
        return index.withFile(indexedFile);
    }

    @Override
    public GroovyProjectIndex withRemovedFile(GroovyProjectIndex index, Path sourceFile) {
        return index.withoutFile(sourceFile);
    }

    private static ProjectTypeIndex.Kind kind(ClassNode node) {
        if (node.isAnnotationDefinition())
            return ProjectTypeIndex.Kind.ANNOTATION;

        if (node.isEnum())
            return ProjectTypeIndex.Kind.ENUM;

        if (node.isRecord())
            return ProjectTypeIndex.Kind.RECORD;

        // Traits only become interfaces in a later phase; after parsing they carry the Trait annotation.
        for (AnnotationNode annotation : node.getAnnotations()) {
            String annotationName = annotation.getClassNode().getName();
            if (annotationName.equals("Trait") || annotationName.equals("groovy.transform.Trait"))
                return ProjectTypeIndex.Kind.TRAIT;
        }

        return node.isInterface() ? ProjectTypeIndex.Kind.INTERFACE : ProjectTypeIndex.Kind.CLASS;
    }

    private static int nameOffset(String text, ClassNode node, String simpleName) {
        int start = node.getLineNumber() < 1
            ? 0
            : GroovyCompiler.offset(text, node.getLineNumber(),
                node.getColumnNumber());
        Matcher matcher = Pattern.compile("(?<![\\w$])" + Pattern.quote(simpleName) + "(?![\\w$])").matcher(text);
        return matcher.find(start) ? matcher.start() : -1;
    }
}
