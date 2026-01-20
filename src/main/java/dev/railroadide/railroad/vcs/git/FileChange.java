package dev.railroadide.railroad.vcs.git;

import java.nio.file.Path;

public record FileChange(
    Path path,
    Path oldPath,
    char indexStatus,
    char workTreeStatus
) {
    public FileChange(Path path, char indexStatus, char workTreeStatus) {
        this(path, null, indexStatus, workTreeStatus);
    }

    public boolean isUntracked() {
        return indexStatus == '?' && workTreeStatus == '?';
    }

    public boolean isConflict() {
        return indexStatus == 'U' || workTreeStatus == 'U';
    }
}
