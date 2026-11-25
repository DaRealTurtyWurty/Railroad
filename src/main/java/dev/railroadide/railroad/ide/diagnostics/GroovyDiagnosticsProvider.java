package dev.railroadide.railroad.ide.diagnostics;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.ide.ProjectWorkspaceConfiguration;
import dev.railroadide.railroad.ide.diagnostics.EditorDiagnostic.TextEditorDiagnostic;
import dev.railroadide.railroad.ide.language.jvm.JvmSourceSets;
import dev.railroadide.railroad.ide.sst.document.api.DocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.Location;
import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import dev.railroadide.railroad.ide.sst.request.api.CancellationToken;
import dev.railroadide.railroad.ide.sst.request.api.Deadline;
import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import dev.railroadide.railroad.ide.sst.request.api.FeatureResult;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.control.ClassNodeResolver;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports Groovy syntax errors and unresolvable class references by compiling each document with the Groovy compiler
 * up to semantic analysis, against the classpath of the source set that owns it.
 * <p>
 * Static type checking runs in a later phase and is not performed, so dynamic code is never reported. Classes
 * declared by the project's Java and Groovy sources resolve by name: a file's package declaration and file name.
 * The compiler never runs project code: AST transformations load only from Groovy itself, so neither global
 * transformations on the project classpath (such as {@code @Grab}) nor project-supplied local transformations run.
 * The compiler resolves JDK classes against the JDK running the IDE.
 */
public final class GroovyDiagnosticsProvider implements DiagnosticsProvider<TextEditorDiagnostic>, AutoCloseable {
    /**
     * Code of an unresolvable class reference.
     */
    public static final String UNRESOLVED_CLASS = "GROOVY_UNRESOLVED_CLASS";
    /**
     * Code of a syntax error.
     */
    public static final String SYNTAX_ERROR = "GROOVY_SYNTAX_ERROR";
    /**
     * Code of any other compilation error.
     */
    public static final String COMPILATION_ERROR = "GROOVY_COMPILATION_ERROR";
    private static final String SOURCE = "railroad:groovy";
    private static final String UNRESOLVED_PREFIX = "unable to resolve class ";
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

    @Override
    public @NotNull List<TextEditorDiagnostic> compute(DocumentSnapshot snapshot) {
        var workspace = new WorkspaceSnapshot("groovy", Map.of(snapshot.id(), snapshot),
            new WorkspaceConfigurationSnapshot(0, Set.of()), 0);
        return compute(new FeatureRequestContext(workspace, snapshot, CancellationToken.none(), Deadline.none()))
            .value();
    }

    @Override
    public @NotNull FeatureResult<List<TextEditorDiagnostic>> compute(FeatureRequestContext context) {
        context.checkActive();
        if (!(context.documentSnapshot() instanceof TextDocumentSnapshot snapshot))
            return new FeatureResult<>(context, List.of());

        WorkspaceConfigurationSnapshot configuration = context.workspaceSnapshot().configuration();
        boolean dependenciesPending = ProjectWorkspaceConfiguration.dependenciesPending(configuration);
        var inputs = JvmSourceSets.compileInputs(configuration, snapshot.uri().filePath().orElse(null));
        Set<String> projectTypes = projectTypes(context);
        GroovyClassLoader loader = loader(inputs.classpath());

        var compilerConfiguration = new CompilerConfiguration();
        compilerConfiguration.setTolerance(100);
        var unit = new CompilationUnit(compilerConfiguration, null, loader, TRANSFORMS);
        unit.setClassNodeResolver(new ProjectClassResolver(projectTypes));
        unit.setProgressCallback((_, _) -> context.checkActive());
        String name = snapshot.uri().filePath().map(path -> path.getFileName().toString()).orElse("Script.groovy");
        unit.addSource(name, snapshot.text());

        List<TextEditorDiagnostic> diagnostics = new ArrayList<>();
        try {
            unit.compile(Phases.CONVERSION);
        } catch (CompilationFailedException failed) {
            report(unit, snapshot, DiagnosticOrigin.SYNTAX, false, diagnostics);
            return new FeatureResult<>(context, DiagnosticsProvider.validated(diagnostics, context));
        }

        context.checkActive();
        try {
            unit.compile(Phases.SEMANTIC_ANALYSIS);
        } catch (CompilationFailedException failed) {
            report(unit, snapshot, DiagnosticOrigin.SEMANTIC, dependenciesPending, diagnostics);
        }

        context.checkActive();
        return new FeatureResult<>(context, DiagnosticsProvider.validated(diagnostics, context));
    }

    private void report(
        CompilationUnit unit,
        TextDocumentSnapshot snapshot,
        DiagnosticOrigin origin,
        boolean dependenciesPending,
        List<TextEditorDiagnostic> diagnostics
    ) {
        for (Message message : unit.getErrorCollector().getErrors()) {
            String text;
            SyntaxException position = null;
            if (message instanceof SyntaxErrorMessage syntax) {
                position = syntax.getCause();
                text = position.getOriginalMessage();
            } else if (message instanceof SimpleMessage simple) {
                text = simple.getMessage();
            } else if (message instanceof ExceptionMessage exception) {
                text = String.valueOf(exception.getCause().getMessage());
            } else
                continue;

            text = text.strip();
            boolean unresolved = text.startsWith(UNRESOLVED_PREFIX);
            if ((unresolved && dependenciesPending) || text.isEmpty())
                continue;

            String code = origin == DiagnosticOrigin.SYNTAX
                ? SYNTAX_ERROR
                : unresolved ? UNRESOLVED_CLASS : COMPILATION_ERROR;
            int[] range = range(snapshot.text(), position);
            diagnostics.add(new TextEditorDiagnostic(Location.TextLocation.from(snapshot, range[0], range[1]),
                DiagnosticSeverity.ERROR, code, text, SOURCE, origin, Set.of(), List.of(), null));
        }
    }

    /**
     * Converts the compiler's one-based line and column range to UTF-16 offsets, extending an empty range over the
     * identifier at its start so the diagnostic remains visible.
     */
    private static int[] range(String text, @Nullable SyntaxException position) {
        if (position == null || position.getStartLine() < 1)
            return new int[]{0, Math.min(1, text.length())};

        int start = offset(text, position.getStartLine(), position.getStartColumn());
        int end = position.getEndLine() < 1 ? start : offset(text, position.getEndLine(), position.getEndColumn());
        if (end <= start) {
            end = start;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
                end++;
            }

            if (end == start && end < text.length() && text.charAt(end) != '\n') {
                end++;
            }
        }

        return new int[]{start, end};
    }

    private static int offset(String text, int line, int column) {
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

    private Set<String> projectTypes(FeatureRequestContext context) {
        Set<String> names = new HashSet<>();
        for (DocumentSnapshot document : context.workspaceSnapshot().documents().values()) {
            context.checkActive();
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
            return loaders.get(List.copyOf(classpath), () -> {
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
