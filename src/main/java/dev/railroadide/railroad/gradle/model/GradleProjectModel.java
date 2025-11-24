package dev.railroadide.railroad.gradle.model;

import dev.railroadide.locatedependencies.ConfigurationTree;
import dev.railroadide.railroad.gradle.model.task.GradleTaskModel;

import java.nio.file.Path;
import java.util.List;

/**
 * Captures information about a single Gradle project within a composite build.
 *
 * @param path               the colon-separated path identifying the project in the build
 * @param name               the user-visible name of the project
 * @param projectDir         the directory where the project resides
 * @param tasks              the tasks exposed by this project
 * @param configurationTrees the configuration trees for dependencies in this project
 */
public record GradleProjectModel(String path, String name, Path projectDir,
                                 List<GradleTaskModel> tasks, List<ConfigurationTree> configurationTrees) {
}
