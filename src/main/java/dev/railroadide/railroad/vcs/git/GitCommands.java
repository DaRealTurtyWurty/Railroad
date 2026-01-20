package dev.railroadide.railroad.vcs.git;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class GitCommands {
    private GitCommands() {
    }

    public static GitCommand statusPorcelainV1Z(GitRepository repo) {
        return GitCommand.builder()
            .workingDirectory(repo)
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("status", "--porcelain=v1", "-b", "-z")
            .build();
    }

    public static GitCommand revParseIsInsideWorkTree(Path repoPath) {
        return GitCommand.builder()
            .workingDirectory(repoPath)
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("rev-parse", "--is-inside-work-tree")
            .build();
    }

    public static GitCommand revParseShowTopLevel(Path repoPath) {
        return GitCommand.builder()
            .workingDirectory(repoPath)
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("rev-parse", "--show-toplevel")
            .build();
    }

    public static GitCommand stageFiles(GitRepository repo, String... filePaths) {
        GitCommand.Builder builder = GitCommand.builder()
            .workingDirectory(repo)
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("add", "--")
            .addArgs(filePaths);

        return builder.build();
    }

    public static GitCommand unstageFiles(GitRepository repo, String... filePaths) {
        GitCommand.Builder builder = GitCommand.builder()
            .workingDirectory(repo)
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("restore", "--staged", "--")
            .addArgs(filePaths);

        return builder.build();
    }
}
