package dev.railroadide.railroad.ide.sst.provider.api;

import java.util.Objects;

/** Identifies an optional service by a namespaced name and its public contract. */
public record CapabilityKey<T>(String id, Class<T> type) {
    public CapabilityKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_.-]+"))
            throw new IllegalArgumentException("Capability ID must be namespaced: " + id);
    }
}
