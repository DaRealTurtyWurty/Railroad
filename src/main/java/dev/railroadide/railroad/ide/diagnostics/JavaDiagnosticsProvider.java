package dev.railroadide.railroad.ide.diagnostics;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.ide.diagnostics.EditorDiagnostic.TextEditorDiagnostic;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.language.impl.JavaLanguageSupport;
import dev.railroadide.railroad.ide.sst.document.api.DocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.DocumentUri;
import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.Location.TextLocation;
import dev.railroadide.railroad.ide.sst.impl.java.JavaSemanticAnalyzer;
import dev.railroadide.railroad.ide.sst.project.JavaSymbolIndex;
import dev.railroadide.railroad.ide.sst.semantic.api.SemanticDiagnostic;
import dev.railroadide.railroad.ide.sst.semantic.api.SemanticModel;
import dev.railroadide.railroad.ide.sst.provider.api.ProviderExecutor;
import dev.railroadide.railroad.ide.sst.request.api.*;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.plugin.spi.inspection.JavaInspectionRuleProvider;
import dev.railroadide.railroad.plugin.spi.inspection.JavaRuleContext;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;

/**
 * Diagnostics provider backed by the SST semantic analyzer.
 *
 * @param project project being analyzed
 * @param projectIndex shared symbol index, or null to resolve it from the project
 */
public record JavaDiagnosticsProvider(
    Project project,
    @Nullable JavaSymbolIndex projectIndex
) implements DiagnosticsProvider<TextEditorDiagnostic> {
    private static final Duration INSPECTION_BUDGET = Duration.ofSeconds(5);

    @Override
    public @NotNull FeatureResult<List<TextEditorDiagnostic>> compute(FeatureRequestContext context) {
        context.checkActive();
        JavaSymbolIndex index = projectIndex == null
            ? JavaLanguageSupport.analysisContextProvider().index(context)
            : projectIndex;
        List<TextEditorDiagnostic> diagnostics = compute(context.documentSnapshot(), index, context);
        context.checkActive();
        return new FeatureResult<>(context, diagnostics);
    }

    @Override
    public @NotNull List<TextEditorDiagnostic> compute(
        WorkspaceSnapshot workspace,
        DocumentId id
    ) {
        var context = new FeatureRequestContext(workspace, workspace.document(id).orElseThrow(),
            () -> Thread.currentThread().isInterrupted(), Deadline.none());
        return compute(context).value();
    }
    /**
     * Creates diagnostics without project indexing; the snapshot supplies the document URI.
     */
    public JavaDiagnosticsProvider() {
        this(null, null);
    }

    /**
     * Creates diagnostics that resolve a symbol index from the supplied project.
     *
     * @param project the project being analyzed
     */
    public JavaDiagnosticsProvider(Project project) {
        this(project, null);
    }

    /**
     * Creates diagnostics using the symbol index shared by a project scan.
     *
     * @param context the project and semantic context to inspect
     */
    public JavaDiagnosticsProvider(ProjectDiagnosticsContext context) {
        this(context.project(), context.javaSymbolIndex());
    }

    @Override
    public @NotNull List<TextEditorDiagnostic> compute(DocumentSnapshot snapshot) {
        var workspace = new WorkspaceSnapshot("standalone-java-diagnostics", Map.of(snapshot.id(), snapshot),
            new WorkspaceConfigurationSnapshot(0, Set.of()), 0);
        var context = new FeatureRequestContext(workspace, snapshot, () -> Thread.currentThread().isInterrupted(),
            Deadline.none());
        JavaSymbolIndex index = projectIndex;
        if (index == null && project != null) {
            index = JavaLanguageSupport.analysisContextProvider().index(project);
        }
        return compute(snapshot, index, context);
    }

    private List<TextEditorDiagnostic> compute(
        DocumentSnapshot snapshot,
        JavaSymbolIndex symbolIndex,
        FeatureRequestContext context
    ) {
        context.checkActive();
        var language = LanguageSupportRegistry.getExpected(JavaLanguageSupport.LANGUAGE_ID);
        Optional<String> snapshotText = TextDocumentSnapshot.unwrap(snapshot, language);

        if (snapshotText.isEmpty())
            return List.of();

        String document = snapshotText.get();

        SemanticModel semanticModel = JavaSemanticAnalyzer.analyzeFacts(context, symbolIndex);

        List<SemanticDiagnostic> semanticDiagnostics = runRegisteredInspections(snapshot.uri(),
            document, semanticModel, symbolIndex, context);
        char[] source = document.toCharArray();

        List<TextEditorDiagnostic> diagnostics = new ArrayList<>();
        for (SemanticDiagnostic diagnostic : semanticDiagnostics) {
            context.checkActive();
            Diagnostic.Kind kind = switch (diagnostic.severity()) {
                case ERROR -> Diagnostic.Kind.ERROR;
                case WARNING -> Diagnostic.Kind.WARNING;
                case INFO -> Diagnostic.Kind.NOTE;
            };

            int start = Math.clamp(diagnostic.startOffset(), 0, source.length);
            int end = Math.clamp(diagnostic.endOffset(), start, source.length);
            diagnostics.add(
                new TextEditorDiagnostic(
                    TextLocation.from((TextDocumentSnapshot) snapshot, start, end),
                    kind,
                    diagnostic.code(),
                    diagnostic.message()));
        }

        context.checkActive();
        return List.copyOf(diagnostics);
    }

    private List<SemanticDiagnostic> runRegisteredInspections(
        DocumentUri documentUri,
        String document,
        SemanticModel semanticModel,
        JavaSymbolIndex symbolIndex,
        FeatureRequestContext request
    ) {
        List<SemanticDiagnostic> diagnostics = new ArrayList<>();

        for (JavaInspectionRuleProvider provider : sortedRuleProviders()) {
            request.checkActive();
            if (provider == null)
                continue;

            try {
                List<SemanticDiagnostic> produced = ProviderExecutor.shared().executeWithContext(
                    "inspection:java:" + provider.id(), request, INSPECTION_BUDGET, child -> {
                        var context = new JavaRuleContext(documentUri, document, semanticModel, symbolIndex,
                            child::checkActive);
                        return JavaInspectionRuleEngine.collectDiagnostics(provider, context);
                    });
                request.checkActive();
                diagnostics.addAll(produced);
            } catch (CancellationException exception) {
                request.checkActive();
                // A provider's own cancellation or deadline does not cancel healthy sibling providers.
            } catch (Exception exception) {
                Railroad.LOGGER.error("Plugin Java inspection rule provider '{}' failed for {}", provider.id(),
                    documentUri, exception);
            }
        }

        request.checkActive();
        return List.copyOf(diagnostics);
    }

    private static List<JavaInspectionRuleProvider> sortedRuleProviders() {
        return JavaInspectionRegistries.ruleProviderEntries().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList();
    }
}
