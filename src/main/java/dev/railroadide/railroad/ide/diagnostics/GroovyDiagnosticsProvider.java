package dev.railroadide.railroad.ide.diagnostics;

import dev.railroadide.railroad.ide.ProjectWorkspaceConfiguration;
import dev.railroadide.railroad.ide.diagnostics.EditorDiagnostic.TextEditorDiagnostic;
import dev.railroadide.railroad.ide.language.impl.groovy.GroovyCompiler;
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
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports Groovy syntax errors and unresolvable class references by compiling each document with
 * {@link GroovyCompiler} against the classpath of the source set that owns it. While build dependencies are still
 * being imported, unresolvable class references are held back.
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

    private final GroovyCompiler compiler;
    private final boolean ownsCompiler;

    /**
     * Creates diagnostics with a compiler of their own, closed with this provider.
     */
    public GroovyDiagnosticsProvider() {
        this(new GroovyCompiler(), true);
    }

    /**
     * Creates diagnostics sharing a session's compiler, which the session closes.
     *
     * @param compiler session compiler
     */
    public GroovyDiagnosticsProvider(GroovyCompiler compiler) {
        this(compiler, false);
    }

    private GroovyDiagnosticsProvider(GroovyCompiler compiler, boolean ownsCompiler) {
        this.compiler = compiler;
        this.ownsCompiler = ownsCompiler;
    }

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
        var inputs = JvmSourceSets.compileInputs(configuration, snapshot.uri().filePath().orElse(null));
        GroovyCompiler.Result result = compiler.compile(snapshot, inputs.classpath(),
            compiler.projectTypes(context.workspaceSnapshot(), context::checkActive), context::checkActive);

        List<TextEditorDiagnostic> diagnostics = new ArrayList<>();
        DiagnosticOrigin origin = result.syntaxFailed() ? DiagnosticOrigin.SYNTAX : DiagnosticOrigin.SEMANTIC;
        boolean dependenciesPending = !result.syntaxFailed()
            && ProjectWorkspaceConfiguration.dependenciesPending(configuration);
        for (Message message : result.errors()) {
            context.checkActive();
            report(message, snapshot, origin, dependenciesPending, diagnostics);
        }

        return new FeatureResult<>(context, DiagnosticsProvider.validated(diagnostics, context));
    }

    private static void report(
        Message message,
        TextDocumentSnapshot snapshot,
        DiagnosticOrigin origin,
        boolean dependenciesPending,
        List<TextEditorDiagnostic> diagnostics
    ) {
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
            return;

        text = text.strip();
        boolean unresolved = text.startsWith(UNRESOLVED_PREFIX);
        if ((unresolved && dependenciesPending) || text.isEmpty())
            return;

        String code = origin == DiagnosticOrigin.SYNTAX
            ? SYNTAX_ERROR
            : unresolved ? UNRESOLVED_CLASS : COMPILATION_ERROR;
        int[] range = range(snapshot.text(), position);
        diagnostics.add(new TextEditorDiagnostic(Location.TextLocation.from(snapshot, range[0], range[1]),
            DiagnosticSeverity.ERROR, code, text, SOURCE, origin, Set.of(), List.of(), null));
    }

    /**
     * Converts the compiler's one-based line and column range to UTF-16 offsets, extending an empty range over the
     * identifier at its start so the diagnostic remains visible.
     */
    private static int[] range(String text, @Nullable SyntaxException position) {
        if (position == null || position.getStartLine() < 1)
            return new int[]{0, Math.min(1, text.length())};

        int start = GroovyCompiler.offset(text, position.getStartLine(), position.getStartColumn());
        int end = position.getEndLine() < 1
            ? start
            : GroovyCompiler.offset(text, position.getEndLine(), position.getEndColumn());
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

    @Override
    public void close() {
        if (ownsCompiler) {
            compiler.close();
        }
    }
}
