package dev.railroadide.railroad.vcs.git;

import java.nio.file.Path;
import java.util.List;
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

    public static GitCommand commit(GitRepository repo, GitCommitData commit) {
        GitCommand.Builder builder = GitCommand.builder()
            .workingDirectory(repo)
            .timeout(10, TimeUnit.SECONDS)
            .addArgs("commit", "-m", commit.message());

        if (commit.description() != null && !commit.description().isBlank()) {
            builder.addArgs("-m", commit.description());
        }

        if (commit.amend()) {
            builder.addArgs("--amend");
        }

        if (commit.signOff()) {
            builder.addArgs("--signoff");
        }

        List<FileChange> fileChanges = commit.selectedChanges();
        if (!fileChanges.isEmpty()) {
            builder.addArgs("--");
            for (FileChange change : fileChanges) {
                if (change == null || change.path() == null)
                    continue;

                builder.addArgs(change.path());
            }
        }

        return builder.build();
    }

    public static GitCommand push(GitRepository repo) {
        return GitCommand.builder()
            .workingDirectory(repo)
            .timeout(15, TimeUnit.SECONDS)
            .addArgs("push")
            .build();
    }
}
