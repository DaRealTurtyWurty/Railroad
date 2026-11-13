package dev.railroadide.railroad.ide.sst.provider.api;

import java.util.Objects;

/** Namespaced identity shared by all dialects of one language. */
public record LanguageId(String value) implements Comparable<LanguageId> {
    public LanguageId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_.-]+"))
            throw new IllegalArgumentException("Language ID must be namespaced: " + value);
    }

    @Override
    public int compareTo(LanguageId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
