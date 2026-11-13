package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import dev.railroadide.railroad.ide.sst.request.api.FeatureResult;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxTree;

/**
 * Parses a captured text revision. Trees retain the exact input snapshot.
 * Providers check request activity during work; unsupported content returns a null payload.
 */
@FunctionalInterface
public interface ParsingProvider {
    /** @return immutable syntax for this request; failure and cancellation propagate */
    FeatureResult<SyntaxTree> parse(FeatureRequestContext context);
}
