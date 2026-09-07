package dev.railroadide.railroad.debug.model;

public record DebugFrameId(
    long suspensionGeneration,
    long threadId,
    int frameIndex
) {
}
