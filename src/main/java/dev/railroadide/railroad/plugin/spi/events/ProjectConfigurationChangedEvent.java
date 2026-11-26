package dev.railroadide.railroad.plugin.spi.events;

import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.plugin.spi.event.Event;
import org.jspecify.annotations.Nullable;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Announces a change to project build or analysis configuration.
 *
 * @param project owner of the changed configuration
 * @param buildFile changed build input on disk, or null for a settings invalidation
 */
public record ProjectConfigurationChangedEvent(Project project, @Nullable Path buildFile) implements Event {
    /**
     * Creates a configuration invalidation event.
     *
     * @param project configuration owner
     * @param buildFile changed build input, if known
     */
    public ProjectConfigurationChangedEvent {
        Objects.requireNonNull(project, "project");
    }

    /**
     * Invalidates project settings without reporting a build-file save.
     *
     * @param project configuration owner
     */
    public ProjectConfigurationChangedEvent(Project project) {
        this(project, null);
    }
}
