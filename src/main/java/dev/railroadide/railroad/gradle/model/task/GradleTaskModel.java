package dev.railroadide.railroad.gradle.model.task;

import dev.railroadide.railroad.gradle.model.GradleProjectModel;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Describes a Gradle task, including metadata required for display and execution.
 *
 * @param project           the Gradle project that contains this task
 * @param path              the full task path (for example {@code :project:task})
 * @param name              the human-readable task name
 * @param group             the Gradle group used to categorize the task
 * @param description       the task description provided by Gradle
 * @param category          a user-friendly category for the task
 * @param isIncrementalSafe whether the task can run incrementally without side effects
 * @param arguments         the arguments that Gradle will accept for this task
 */
public record GradleTaskModel(GradleProjectModel project, String path, String name, String group, String description,
                              String category, boolean isIncrementalSafe,
                              List<GradleTaskArgument> arguments) {
    @Override
    public int hashCode() {
        return path.hashCode() + project.path().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || getClass() != obj.getClass())
            return false;

        GradleTaskModel that = (GradleTaskModel) obj;
        return isIncrementalSafe == that.isIncrementalSafe && Objects.equals(path, that.path) &&
            Objects.equals(name, that.name) && Objects.equals(group, that.group) &&
            Objects.equals(category, that.category) && Objects.equals(description, that.description) &&
            Objects.equals(project, that.project) && Objects.equals(arguments, that.arguments);
    }

    @Override
    public @NotNull String toString() {
        return "GradleTaskModel{" +
            "project=" + project.path() +
            ", path='" + path + '\'' +
            ", name='" + name + '\'' +
            ", group='" + group + '\'' +
            ", description='" + description + '\'' +
            ", category='" + category + '\'' +
            ", isIncrementalSafe=" + isIncrementalSafe +
            ", arguments=" + arguments +
            '}';
    }
}
