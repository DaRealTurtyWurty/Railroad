package dev.railroadide.railroad.debug.source;

import java.util.Optional;

public interface DependencySourceIndex {
    Optional<DebugSource> resolve(String runtimeSourcePath, String declaringType);
}
