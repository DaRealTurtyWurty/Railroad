package dev.railroadide.railroad.vcs.git.status;

import java.nio.file.Path;

public record GitFileChange(
    Path path,
    Path oldPath,
    char indexStatus,
    char workTreeStatus
) {
    public GitFileChange(Path path, char indexStatus, char workTreeStatus) {
        this(path, null, indexStatus, workTreeStatus);
    }

    public boolean isUntracked() {
        return indexStatus == '?' && workTreeStatus == '?';
    }

    public boolean isConflict() {
        return indexStatus == 'U' || workTreeStatus == 'U';
    }

    public boolean isModified() {
        return indexStatus == 'M' || workTreeStatus == 'M';
    }

    public boolean isAdded() {
        return indexStatus == 'A' || workTreeStatus == 'A';
    }

    public boolean isDeleted() {
        return indexStatus == 'D' || workTreeStatus == 'D';
    }

    public boolean isRenamed() {
        return indexStatus == 'R' || workTreeStatus == 'R';
    }

    public boolean isCopied() {
        return indexStatus == 'C' || workTreeStatus == 'C';
    }

    public boolean isUnchanged() {
        return indexStatus == ' ' && workTreeStatus == ' ';
    }

    public boolean isStaged() {
        return indexStatus != ' ' && !isUntracked();
    }

    public boolean isUnstaged() {
        return workTreeStatus != ' ' && !isUntracked();
    }
}
