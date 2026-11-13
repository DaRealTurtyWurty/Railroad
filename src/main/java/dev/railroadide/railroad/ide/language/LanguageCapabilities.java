package dev.railroadide.railroad.ide.language;

import dev.railroadide.railroad.ide.language.index.*;
import dev.railroadide.railroad.ide.sst.provider.api.CapabilityKey;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.ide.ui.codeeditor.SyntaxHighlightingProvider;

/** Host integration contracts; portable language features belong to SstCapabilities. */
public final class LanguageCapabilities {
    public static final CapabilityKey<Project> PROJECT = new CapabilityKey<>("railroad:project", Project.class);
    public static final CapabilityKey<ProjectLanguageIndexer> INDEXER = new CapabilityKey<>("railroad:project-indexer",
        ProjectLanguageIndexer.class);
    public static final CapabilityKey<ProjectLanguageIndexPersistence> PERSISTENCE = new CapabilityKey<>(
        "railroad:index-persistence", ProjectLanguageIndexPersistence.class);
    public static final CapabilityKey<LanguageIndexContextContributor> INDEX_CONTEXT = new CapabilityKey<>(
        "railroad:index-context", LanguageIndexContextContributor.class);
    public static final CapabilityKey<IndexWarmup> INDEX_WARMUP = new CapabilityKey<>("railroad:index-warmup",
        IndexWarmup.class);

    public static final CapabilityKey<SyntaxHighlightingProvider> HIGHLIGHTING = new CapabilityKey<>(
        "railroad:highlighting", SyntaxHighlightingProvider.class);

    private LanguageCapabilities() {
    }

    @FunctionalInterface
    public interface IndexWarmup {
        void warm(ProjectIndexContext context);
    }
}
