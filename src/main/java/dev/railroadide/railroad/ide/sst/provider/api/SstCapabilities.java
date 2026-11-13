package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.completion.CompletionProvider;
import dev.railroadide.railroad.ide.diagnostics.DiagnosticsProvider;
import dev.railroadide.railroad.ide.signature.SignatureHelpProvider;

/** Standard optional SST contracts. Providers may also publish their own typed keys. */
public final class SstCapabilities {
    public static final CapabilityKey<DiagnosticsProvider> DIAGNOSTICS = new CapabilityKey<>("railroad:diagnostics",
        DiagnosticsProvider.class);
    public static final CapabilityKey<CompletionProvider> COMPLETION = new CapabilityKey<>("railroad:completion",
        CompletionProvider.class);
    public static final CapabilityKey<SignatureHelpProvider> SIGNATURE_HELP = new CapabilityKey<>(
        "railroad:signature-help", SignatureHelpProvider.class);
    public static final CapabilityKey<ParsingProvider> PARSING = new CapabilityKey<>("railroad:parsing",
        ParsingProvider.class);
    public static final CapabilityKey<SemanticProvider> SEMANTICS = new CapabilityKey<>("railroad:semantics",
        SemanticProvider.class);
    public static final CapabilityKey<IndexExtractionProvider> INDEX_EXTRACTION = new CapabilityKey<>(
        "railroad:index-extraction", IndexExtractionProvider.class);
    public static final CapabilityKey<IndexQueryProvider> INDEX_QUERY = new CapabilityKey<>("railroad:index-query",
        IndexQueryProvider.class);
    public static final CapabilityKey<NavigationProvider> NAVIGATION = new CapabilityKey<>("railroad:navigation",
        NavigationProvider.class);
    public static final CapabilityKey<CodeActionProvider> CODE_ACTIONS = new CapabilityKey<>("railroad:code-actions",
        CodeActionProvider.class);

    private SstCapabilities() {
    }
}
