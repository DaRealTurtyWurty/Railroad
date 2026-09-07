package dev.railroadide.railroad.debug.source;

import java.util.Optional;

public interface DependencySourceIndex {
    DependencySourceIndex EMPTY = (_, _) -> Optional.empty();
    Optional<DebugSource> resolve(String runtimeSourcePath, String declaringType);
}
