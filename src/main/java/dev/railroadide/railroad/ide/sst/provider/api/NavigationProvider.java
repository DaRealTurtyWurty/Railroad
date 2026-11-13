package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.document.api.Location;
import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import dev.railroadide.railroad.ide.sst.request.api.FeatureResult;
import java.util.List;

/** Resolves a source position to versioned targets without opening an editor. */
@FunctionalInterface
public interface NavigationProvider {
    /**
     * Resolves definitions at a position in the captured text document.
     *
     * @param context captured document, workspace, cancellation token, and deadline
     * @param offset UTF-16 offset of the source position to resolve
     * @return targets for a UTF-16 text offset, or an empty list when no target exists
     */
    FeatureResult<List<Location<?>>> definitions(FeatureRequestContext context, int offset);
}
