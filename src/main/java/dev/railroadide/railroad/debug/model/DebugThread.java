package dev.railroadide.railroad.debug.model;

public record DebugThread(
    long id,
    String name,
    int status,
    boolean suspended
) {
}
