package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import dev.railroadide.railroad.ide.sst.request.api.FeatureResult;
import java.util.List;

/** Extracts immutable declarations from one captured text or binary document. */
@FunctionalInterface
public interface IndexExtractionProvider {
    /** @return declarations at this revision; an empty list means no declarations */
    FeatureResult<List<SymbolOccurrence>> extract(FeatureRequestContext context);
}
