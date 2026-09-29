package dev.railroadide.railroad.debug.model;

public record DebugVariable(
    String name,
    String type,
    String value,
    long childrenReference,
    int indexedChildren
) {
    public boolean hasChildren() {
        return childrenReference > 0;
    }
}
