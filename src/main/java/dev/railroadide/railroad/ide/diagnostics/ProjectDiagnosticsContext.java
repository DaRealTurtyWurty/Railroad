package dev.railroadide.railroad.ide.diagnostics;

import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.language.index.DefaultProjectIndexContextResolver;
import dev.railroadide.railroad.ide.language.index.ProjectIndexContext;
import dev.railroadide.railroad.plugin.spi.dto.Project;

import java.util.Objects;

/** Captured project roots and cancellation controls shared by a diagnostics export. */
public record ProjectDiagnosticsContext(Project project, ProjectIndexContext indexContext) {
    public ProjectDiagnosticsContext {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(indexContext, "indexContext");
    }

    /** Resolves language-neutral project inputs; each language session owns its analysis state. */
    public static ProjectDiagnosticsContext create(Project project) {
        Objects.requireNonNull(project, "project");
        ProjectIndexContext indexContext = new DefaultProjectIndexContextResolver(
            Services.PROJECT_LANGUAGE_INDEX_SERVICE::session).resolve(project);
        return new ProjectDiagnosticsContext(project, indexContext);
    }
}
