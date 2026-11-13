package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.request.api.FeatureRequestContext;
import dev.railroadide.railroad.ide.sst.request.api.FeatureResult;
import java.util.List;

/** Computes immutable proposed edits; computation never mutates a live document. */
@FunctionalInterface
public interface CodeActionProvider {
    /**
     * Computes proposed fixes for the captured document without applying them.
     *
     * @param context captured document, workspace, cancellation token, and deadline
     * @return actions for the captured document; an empty list means no applicable actions
     */
    FeatureResult<List<CodeAction>> actions(FeatureRequestContext context);
}
