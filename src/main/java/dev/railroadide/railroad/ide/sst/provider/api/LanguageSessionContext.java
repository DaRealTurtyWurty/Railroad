package dev.railroadide.railroad.ide.sst.provider.api;

import java.nio.file.Path;
import java.util.Objects;

/** Captured project location, dialect and explicitly supplied host services for a session. */
public record LanguageSessionContext(Path projectRoot, String dialect, CapabilityRegistry services) {
    public LanguageSessionContext {
        projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(services, "services");
    }
}
