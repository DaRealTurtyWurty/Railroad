package dev.railroadide.railroad.vcs.git.diff;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

public record DiffFile(
    @Nullable Path oldPath,
    @Nullable Path newPath,
    boolean isBinary,
    List<String> headers,
    List<DiffHunk> hunks
) {
    public boolean isNewFile() {
        return oldPath == null;
    }

    public boolean isDeletedFile() {
        return newPath == null;
    }

    public static DiffFile binary(DiffFile file) {
        return new DiffFile(
            file.oldPath,
            file.newPath,
            true,
            file.headers,
            file.hunks
        );
    }

    public static DiffFile setOldPath(DiffFile file, String oldPath) {
        return new DiffFile(
            Path.of(oldPath).normalize(),
            file.newPath,
            file.isBinary,
            file.headers,
            file.hunks
        );
    }

    public static DiffFile setNewPath(DiffFile file, String newPath) {
        return new DiffFile(
            file.oldPath,
            Path.of(newPath).normalize(),
            file.isBinary,
            file.headers,
            file.hunks
        );
    }
}
