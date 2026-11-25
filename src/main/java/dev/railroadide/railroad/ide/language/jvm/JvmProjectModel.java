package dev.railroadide.railroad.ide.language.jvm;

import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Source roots, binary inputs and JDK of a JVM project, resolved from its build tool. Shared by every JVM language;
 * each language selects the source roots it indexes.
 *
 * @param sourceRoots source directories
 * @param generatedRoots generated source directories
 * @param dependencyRoots dependency locations used during analysis
 * @param classpathRoots classpath directories and archives
 * @param modulePathRoots module-path locations
 * @param jdkHome JDK home, or {@code null} to use the running JDK
 * @param gradleConfiguration imported Gradle module settings, or {@code null} for other projects
 */
public record JvmProjectModel(
    List<Path> sourceRoots,
    List<Path> generatedRoots,
    List<Path> dependencyRoots,
    List<Path> classpathRoots,
    List<Path> modulePathRoots,
    @Nullable Path jdkHome,
    @Nullable WorkspaceConfigurationSnapshot gradleConfiguration
) {
    /**
     * Creates a model for a project with one set of binary roots and no imported Gradle settings.
     *
     * @param sourceRoots source directories
     * @param generatedRoots generated source directories
     * @param dependencyRoots dependency locations used during analysis
     * @param classpathRoots classpath directories and archives
     * @param modulePathRoots module-path locations
     * @param jdkHome JDK home, or {@code null} to use the running JDK
     */
    public JvmProjectModel(
        List<Path> sourceRoots,
        List<Path> generatedRoots,
        List<Path> dependencyRoots,
        List<Path> classpathRoots,
        List<Path> modulePathRoots,
        @Nullable Path jdkHome
    ) {
        this(sourceRoots, generatedRoots, dependencyRoots, classpathRoots, modulePathRoots, jdkHome, null);
    }
}
