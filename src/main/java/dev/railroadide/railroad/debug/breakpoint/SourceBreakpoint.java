package dev.railroadide.railroad.debug.breakpoint;

import dev.railroadide.railroad.debug.source.DebugSource;

import java.util.UUID;

public record SourceBreakpoint(
    UUID id,
    DebugSource source,
    int line,
    boolean enabled
) {
}
