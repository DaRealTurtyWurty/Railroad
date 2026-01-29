package dev.railroadide.railroad.vcs.git;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public record CommitPage(List<GitCommit> commits, @Nullable String nextCursor) {}
