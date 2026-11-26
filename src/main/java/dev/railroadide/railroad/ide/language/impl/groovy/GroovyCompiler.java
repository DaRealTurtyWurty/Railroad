package dev.railroadide.railroad.ide.language.impl.groovy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.ide.sst.document.api.DocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.ClassNodeResolver;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.Message;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles Groovy documents up to semantic analysis for the session's diagnostics and declarations, caching each
 * result per document revision, classpath and set of project classes.
 * <p>
 * Static type checking runs in a later phase and is not performed. Classes declared by the project's Java and Groovy
 * sources resolve by name: a file's package declaration and file name. The compiler never runs project code: AST
 * transformations load only from Groovy itself, so neither global transformations on the project classpath (such as
 * {@code @Grab}) nor project-supplied local transformations run. JDK classes resolve against the JDK running the IDE.
 * Compilation results are confined to the thread that produced them until cached; cached results are only read.
 */
public final class GroovyCompiler implements AutoCloseable {
    private static final Set<String> SOURCE_LANGUAGES = Set.of("java", "groovy");
    private static final Pattern PACKAGE = Pattern.compile(
        "\\A(?:\\s|//[^\\n]*\\n|/\\*.*?\\*/|#![^\\n]*\\n|@[\\w.]+(?:\\([^)]*\\))?)*package\\s+([\\w.]+)",
        Pattern.DOTALL);
    private static final int PACKAGE_SCAN_LIMIT = 8192;
    private static final ClassLoader GROOVY_ONLY = new GroovyOnlyClassLoader();
    private static final GroovyClassLoader TRANSFORMS = new GroovyClassLoader(GROOVY_ONLY);

    private final Cache<List<Path>, GroovyClassLoader> loaders = CacheBuilder.newBuilder().maximumSize(4)
        .removalListener((RemovalListener<List<Path>, GroovyClassLoader>) removal -> close(removal.getValue()))
        .build();
    private final Cache<DocumentSnapshot, String> declaredNames = CacheBuilder.newBuilder().weakKeys()
        .maximumSize(10_000).build();
    private final Cache<Key, Result> results = CacheBuilder.newBuilder().maximumSize(256).build();

    /**
     * The outcome of compiling one document revision.
     *
     * @param module the document's syntax tree with resolved class references, or {@code null} if parsing failed
     * @param errors compiler errors in report order
     * @param syntaxFailed whether the document failed to parse, so {@code errors} are syntax errors
     */
    public record Result(@Nullable ModuleNode module, List<? extends Message> errors, boolean syntaxFailed) {
        /**
         * Copies the error list.
         *
         * @param module the document's syntax tree with resolved class references, or {@code null} if parsing failed
         * @param errors compiler errors in report order, or {@code null} for none as Groovy reports it
         * @param syntaxFailed whether the document failed to parse, so {@code errors} are syntax errors
         */
        public Result {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    private record Key(TextDocumentSnapshot snapshot, List<Path> classpath, Set<String> projectTypes) {
    }

    /**
     * Compiles a document revision, reusing an earlier result for the same inputs.
     *
     * @param snapshot document revision to compile
     * @param classpath binary roots the document compiles against
     * @param projectTypes qualified names of classes declared by project sources
     * @param activityCheck callback that throws once the request is cancelled or expires
     * @return the compilation result
     */
    public Result compile(
        TextDocumentSnapshot snapshot,
        List<Path> classpath,
        Set<String> projectTypes,
        Runnable activityCheck
    ) {
        var key = new Key(snapshot, List.copyOf(classpath), Set.copyOf(projectTypes));
        Result cached = results.getIfPresent(key);
        if (cached != null)
            return cached;

        var configuration = new CompilerConfiguration();
        configuration.setTolerance(100);
        var unit = new CompilationUnit(configuration, null, loader(key.classpath()), TRANSFORMS);
        unit.setClassNodeResolver(new ProjectClassResolver(key.projectTypes()));
        unit.setProgressCallback((_, _) -> activityCheck.run());
        String name = snapshot.uri().filePath().map(path -> path.getFileName().toString()).orElse("Script.groovy");
        SourceUnit source = unit.addSource(name, snapshot.text());

        Result result;
        try {
            unit.compile(Phases.CONVERSION);
            try {
                unit.compile(Phases.SEMANTIC_ANALYSIS);
            } catch (CompilationFailedException _) {
                // Semantic errors leave a usable tree; they are reported through the error collector.
            }

            result = new Result(source.getAST(), unit.getErrorCollector().getErrors(), false);
        } catch (CompilationFailedException failed) {
            result = new Result(null, unit.getErrorCollector().getErrors(), true);
        }

        activityCheck.run();
        results.put(key, result);
        return result;
    }

    /**
     * Parses source text without resolving class references, which needs no classpath. Used for indexing, where
     * declared names and positions are enough.
     *
     * @param fileName name of the source file, used for script class names
     * @param text source text
     * @param activityCheck callback that throws once the request is cancelled
     * @return the syntax tree, or {@code null} when the text does not parse
     */
    public static @Nullable ModuleNode parse(String fileName, String text, Runnable activityCheck) {
        var configuration = new CompilerConfiguration();
        configuration.setTolerance(100);
        var unit = new CompilationUnit(configuration, null, TRANSFORMS, TRANSFORMS);
        unit.setProgressCallback((_, _) -> activityCheck.run());
        SourceUnit source = unit.addSource(fileName, text);
        try {
            unit.compile(Phases.CONVERSION);
        } catch (CompilationFailedException failed) {
            return null;
        }

        activityCheck.run();
        return source.getAST();
    }

    /**
     * Collects the qualified names of classes declared by the workspace's Java and Groovy sources, taking each from
     * its file's package declaration and file name.
     *
     * @param workspace captured workspace whose documents are read
     * @param activityCheck callback that throws once the request is cancelled or expires
     * @return qualified class names
     */
    public Set<String> projectTypes(WorkspaceSnapshot workspace, Runnable activityCheck) {
        Set<String> names = new HashSet<>();
        for (DocumentSnapshot document : workspace.documents().values()) {
            activityCheck.run();
            if (!(document instanceof TextDocumentSnapshot text) || !SOURCE_LANGUAGES.contains(text.languageId()))
                continue;

            String declared = declaredNames.getIfPresent(text);
            if (declared == null) {
                declared = declaredName(text);
                declaredNames.put(text, declared);
            }

            if (!declared.isEmpty()) {
                names.add(declared);
            }
        }

        return names;
    }

    /**
     * Converts a one-based compiler line and column to a UTF-16 offset, clamped to the line.
     *
     * @param text document text
     * @param line one-based line
     * @param column one-based column
     * @return offset into {@code text}
     */
    public static int offset(String text, int line, int column) {
        int offset = 0;
        for (int current = 1; current < line && offset < text.length(); current++) {
            int next = text.indexOf('\n', offset);
            if (next < 0)
                return text.length();

            offset = next + 1;
        }

        int lineEnd = text.indexOf('\n', offset);
        int limit = lineEnd < 0 ? text.length() : lineEnd;
        return Math.min(offset + Math.max(0, column - 1), limit);
    }

    private static String declaredName(TextDocumentSnapshot document) {
        String fileName = document.uri().filePath().map(path -> path.getFileName().toString()).orElse("");
        int extension = fileName.lastIndexOf('.');
        if (extension <= 0)
            return "";

        String simpleName = fileName.substring(0, extension);
        String text = document.text();
        Matcher matcher = PACKAGE.matcher(text.substring(0, Math.min(text.length(), PACKAGE_SCAN_LIMIT)));
        return matcher.lookingAt() ? matcher.group(1) + "." + simpleName : simpleName;
    }

    private GroovyClassLoader loader(List<Path> classpath) {
        try {
            return loaders.get(classpath, () -> {
                var configuration = new CompilerConfiguration();
                configuration.setClasspathList(classpath.stream().map(Path::toString).toList());
                return new GroovyClassLoader(GROOVY_ONLY, configuration);
            });
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Cannot create Groovy class loader", exception.getCause());
        }
    }

    private static void close(@Nullable GroovyClassLoader loader) {
        if (loader == null)
            return;

        try {
            loader.close();
        } catch (IOException exception) {
            Railroad.LOGGER.warn("Cannot close Groovy class loader", exception);
        }
    }

    @Override
    public void close() {
        results.invalidateAll();
        loaders.invalidateAll();
        loaders.cleanUp();
        declaredNames.invalidateAll();
    }

    /**
     * Resolves classes from the classpath first, then treats classes declared by project sources as resolved.
     */
    private static final class ProjectClassResolver extends ClassNodeResolver {
        private final Set<String> projectTypes;

        private ProjectClassResolver(Set<String> projectTypes) {
            this.projectTypes = projectTypes;
        }

        @Override
        public LookupResult resolveName(String name, CompilationUnit compilationUnit) {
            LookupResult found = super.resolveName(name, compilationUnit);
            if (found != null || !(projectTypes.contains(name) || projectTypes.contains(name.replace('$', '.'))))
                return found;

            return new LookupResult(null, ClassHelper.make(name));
        }
    }

    /**
     * Sees the platform's classes and Groovy's own, but nothing from the IDE's other libraries, so project code
     * resolves only what its own classpath provides.
     */
    private static final class GroovyOnlyClassLoader extends ClassLoader {
        private static final ClassLoader GROOVY = GroovySystem.class.getClassLoader();

        private GroovyOnlyClassLoader() {
            super(ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (isGroovy(name.replace('.', '/')))
                return GROOVY.loadClass(name);

            throw new ClassNotFoundException(name);
        }

        @Override
        protected URL findResource(String name) {
            return isGroovy(name) ? GROOVY.getResource(name) : null;
        }

        @Override
        protected Enumeration<URL> findResources(String name) throws IOException {
            return isGroovy(name) ? GROOVY.getResources(name) : super.findResources(name);
        }

        private static boolean isGroovy(String path) {
            return path.startsWith("groovy/") || path.startsWith("org/codehaus/groovy/")
                || path.startsWith("org/apache/groovy/");
        }
    }
}
