package dev.railroadide.railroad.vcs.git;

import java.util.List;

public record GitCommitData(
    String message,
    String description,
    boolean amend,
    boolean signOff,
    List<FileChange> selectedChanges) {
}
