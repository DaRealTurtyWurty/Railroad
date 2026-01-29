package dev.railroadide.railroad.vcs.git;

import dev.railroadide.railroad.vcs.git.commit.GitCommitData;
import dev.railroadide.railroad.vcs.git.status.GitFileChange;
import dev.railroadide.railroad.vcs.git.util.GitRepository;
import org.jetbrains.annotations.Nullable;

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

        List<GitFileChange> fileChanges = commit.selectedChanges();
        if (!fileChanges.isEmpty()) {
            builder.addArgs("--");
            for (GitFileChange change : fileChanges) {
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
            .addArgs("push", "--progress")
            .build();
    }

    public static GitCommand remoteGetUrls(GitRepository repo) {
        return GitCommand.builder()
            .workingDirectory(repo)
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("remote", "-v")
            .build();
    }

    public static GitCommand getUpstream(GitRepository repo) {
        return GitCommand.builder()
            .workingDirectory(repo)
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
            .build();
    }

    public static GitCommand fetch(GitRepository repo) {
        return GitCommand.builder()
            .workingDirectory(repo)
            .timeout(30, TimeUnit.SECONDS)
            .addArgs("fetch", "--prune", "--progress")
            .build();
    }

    public static GitCommand pull(GitRepository repo) {
        return GitCommand.builder()
            .workingDirectory(repo)
            .timeout(30, TimeUnit.SECONDS)
            .addArgs("pull", "--ff-only", "--progress")
            .build();
    }

    public static GitCommand getUserName() {
        return GitCommand.builder()
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("config", "--get", "user.name")
            .build();
    }

    public static GitCommand getUserEmail() {
        return GitCommand.builder()
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("config", "--get", "user.email")
            .build();
    }

    public static GitCommand getCommitGpgSign() {
        return GitCommand.builder()
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("config", "--get", "commit.gpgsign")
            .build();
    }

    public static GitCommand getGpgFormat() {
        return GitCommand.builder()
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("config", "--get", "gpg.format")
            .build();
    }

    public static GitCommand getUserSigningKey() {
        return GitCommand.builder()
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("config", "--get", "user.signingkey")
            .build();
    }

    public static GitCommand getGpgProgram() {
        return GitCommand.builder()
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("config", "--get", "gpg.program")
            .build();
    }

    public static GitCommand getGitVersion() {
        return GitCommand.builder()
            .timeout(5, TimeUnit.SECONDS)
            .addArgs("--version")
            .build();
    }

    public static GitCommand getRecentCommits(GitRepository repo, @Nullable String cursor, int limit) {
        GitCommand.Builder builder = GitCommand.builder()
            .workingDirectory(repo)
            .timeout(10, TimeUnit.SECONDS)
            .addArgs(
                "--no-pager",
                "log",
                "--first-parent",
                "-n", String.valueOf(limit),
                "--date=unix",
                "--pretty=format:%H%x00%h%x00%s%x00%an%x00%ae%x00%at%x00%P%x1e");

        if (cursor != null && !cursor.isBlank()) {
            builder.addArgs(cursor + "^");
        }

        return builder.build();
    }
}
