package dev.railroadide.railroad.debug.source;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class SourceResolver {
    private final List<Path> sourceRoots;
    private final DependencySourceIndex dependencySources;

    public SourceResolver(List<Path> sourceRoots, DependencySourceIndex dependencySources) {
        this.sourceRoots = sourceRoots.stream()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .toList();
        this.dependencySources = dependencySources;
    }

    public Optional<DebugSource> resolve(Location location) {
        String runtimePath = getRuntimeSourcePath(location);
        if (runtimePath == null)
            return Optional.empty();

        String normalized = runtimePath.replace('/', File.separatorChar);
        for (Path root : sourceRoots) {
            Path candidate = root.resolve(normalized).normalize();
            if (candidate.startsWith(root) && Files.isRegularFile(candidate))
                return Optional.of(new DebugSource.FileSource(candidate));
        }

        return dependencySources.resolve(
            runtimePath,
            location.declaringType().name());
    }

    private String getRuntimeSourcePath(Location location) {
        try {
            return location.sourcePath();
        } catch (AbsentInformationException _) {
        }

        try {
            String sourceName = location.sourceName();
            String className = location.declaringType().name();
            int lastDot = className.lastIndexOf('.');
            if (lastDot == -1)
                return sourceName;

            String packageName = className.substring(0, lastDot)
                .replace('.', '/');
            return packageName + "/" + sourceName;
        } catch (AbsentInformationException _) {
            return null;
        }
    }

    public boolean matches(DebugSource expected, Location location) {
        Optional<DebugSource> actual = resolve(location);
        return actual.isPresent() && expected.matches(actual.get());
    }

    public Optional<String> sourceName(DebugSource source) {
        return Optional.ofNullable(source.name());
    }

    public Optional<String> classPatternFor(DebugSource source) {
        return source.classPattern(sourceRoots);
    }
}
