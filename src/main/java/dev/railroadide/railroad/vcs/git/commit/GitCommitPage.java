package dev.railroadide.railroad.vcs.git.commit;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public record GitCommitPage(List<GitCommit> commits, @Nullable String nextCursor) {}
