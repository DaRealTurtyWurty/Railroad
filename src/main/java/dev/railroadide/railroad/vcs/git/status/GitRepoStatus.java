package dev.railroadide.railroad.vcs.git.status;

import java.util.List;

public record GitRepoStatus(
    String branch,
    int ahead,
    int behind,
    List<GitFileChange> changes
) {}
