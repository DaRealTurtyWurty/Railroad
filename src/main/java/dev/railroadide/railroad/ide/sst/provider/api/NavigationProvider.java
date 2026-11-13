package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.document.api.Location;
import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import dev.railroadide.railroad.ide.sst.request.api.FeatureResult;
import java.util.List;

/** Resolves a source position to versioned targets without opening an editor. */
@FunctionalInterface
public interface NavigationProvider {
    /** @return targets for a UTF-16 text offset, or an empty list when no target exists */
    FeatureResult<List<Location<?>>> definitions(FeatureRequestContext context, int offset);
}
