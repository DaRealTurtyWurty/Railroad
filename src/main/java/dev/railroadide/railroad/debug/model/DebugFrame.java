package dev.railroadide.railroad.debug.model;

import dev.railroadide.railroad.debug.source.DebugSource;

public record DebugFrame(
    DebugFrameId id,
    String displayName,
    DebugSource source,
    int line
) {
}
