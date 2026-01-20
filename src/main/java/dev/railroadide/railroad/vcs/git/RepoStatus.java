package dev.railroadide.railroad.vcs.git;

import java.util.List;

public record RepoStatus(
    String branch,
    int ahead,
    int behind,
    List<FileChange> changes
) {}
