package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.document.api.Location;
import dev.railroadide.railroad.ide.sst.semantic.api.Symbol;
import java.util.Objects;

/** A named declaration and its versioned text or binary location. */
public record SymbolOccurrence(Symbol symbol, Location<?> location) {
    public SymbolOccurrence {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(location, "location");
    }
}
