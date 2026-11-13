package dev.railroadide.railroad.ide.sst.provider.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable typed services. Absence means unsupported; duplicate names are rejected. */
public final class CapabilityRegistry {
    private final Map<String, Entry> entries;

    private CapabilityRegistry(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CapabilityRegistry empty() {
        return builder().build();
    }

    public <T> Optional<T> find(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key.id());
        if (entry == null)
            return Optional.empty();
        if (!entry.key.equals(key))
            throw new IllegalArgumentException("Conflicting capability type for " + key.id());
        return Optional.of(key.type().cast(entry.value));
    }

    /** Returns an immutable snapshot of the registered services for ownership/disposal. */
    public Collection<Object> values() {
        return entries.values().stream().map(Entry::value).toList();
    }

    public Set<CapabilityKey<?>> keys() {
        return entries.values().stream().map(Entry::key).collect(Collectors.toUnmodifiableSet());
    }

    public static final class Builder {
        private final Map<String, Entry> entries = new LinkedHashMap<>();

        private Builder() {
        }

        public <T> Builder add(CapabilityKey<T> key, T value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            key.type().cast(value);
            if (entries.putIfAbsent(key.id(), new Entry(key, value)) != null)
                throw new IllegalArgumentException("Duplicate capability: " + key.id());
            return this;
        }

        public CapabilityRegistry build() {
            return new CapabilityRegistry(entries);
        }
    }

    private record Entry(CapabilityKey<?> key, Object value) {
    }
}
