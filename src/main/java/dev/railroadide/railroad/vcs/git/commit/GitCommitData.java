package dev.railroadide.railroad.vcs.git.commit;

import dev.railroadide.railroad.vcs.git.status.GitFileChange;

import java.util.List;

public record GitCommitData(
    String message,
    String description,
    boolean amend,
    boolean signOff,
    List<GitFileChange> selectedChanges) {
}
