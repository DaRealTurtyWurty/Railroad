package dev.railroadide.railroad.debug.source;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface DebugSource {
    boolean matches(DebugSource other);
    String name();
    Optional<String> classPattern(List<Path> paths);

    record FileSource(Path file) implements DebugSource {
        @Override
        public boolean matches(DebugSource other) {
            return other instanceof FileSource(Path file1) &&
                Objects.equals(file.toAbsolutePath().normalize(), file1.toAbsolutePath().normalize());
        }

        @Override
        public String name() {
            return file.getFileName().toString();
        }

        @Override
        public Optional<String> classPattern(List<Path> paths) {
            Path absolute = file.toAbsolutePath().normalize();
            for (Path root : paths) {
                if (!absolute.startsWith(root))
                    continue;

                Path relative = root.relativize(absolute);
                String className = relative.toString().replace(File.separatorChar, '.');
                if (!className.endsWith(".java"))
                    continue;

                className = className.substring(0, className.length() - ".java".length());

                return Optional.of(className + "*");
            }

            return Optional.empty();
        }
    }

    record ArchiveSource(Path archive, String entry) implements DebugSource {
        @Override
        public boolean matches(DebugSource other) {
            return other instanceof ArchiveSource(Path archive1, String entry1) &&
                Objects.equals(archive, archive1) && Objects.equals(entry, entry1);
        }

        @Override
        public String name() {
            int slash = entry.lastIndexOf('/');
            return slash == -1 ? entry : entry.substring(slash + 1);
        }

        @Override
        public Optional<String> classPattern(List<Path> paths) {
            return Optional.empty(); // TODO: Integrate gradle/maven dependency roots
        }
    }
}
