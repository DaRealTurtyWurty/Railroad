package dev.railroadide.railroad.vcs.git;

import dev.railroadide.railroad.Railroad;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

// TODO: Add small FS cache for detected repositories to avoid repeated git calls
// TODO: Integrate the use of IDE tasks
public class GitClient {
    protected final GitProcessRunner runner;

    public GitClient(GitProcessRunner runner) {
        this.runner = runner;
    }

    public RepoStatus getStatus(GitRepository repo) {
        GitCommand cmd = GitCommands.statusPorcelainV1Z(repo);
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.NULL_RECORDS);

        if (result.timedOut())
            throw new GitExecutionException("git status timed out");

        if (result.cancelled())
            throw new GitExecutionException("git status was cancelled");

        if (result.exitCode() != 0)
            throw new GitExecutionException("git status failed: " + String.join("\n", result.stderr()));

        return GitStatusParser.parsePorcelainV1Z(repo, result.stdout());
    }

    public Optional<GitRepository> detectRepository(Path path) {
        GitCommand isInsideCmd = GitCommands.revParseIsInsideWorkTree(path);
        GitResult isInsideResult = runner.run(isInsideCmd, null, null, ResultCaptureMode.TEXT_LINES);
        if (isInsideResult.timedOut()) {
            Railroad.LOGGER.warn("git {} timed out for path: {}", isInsideCmd.argsString(), path);
            return Optional.empty();
        }

        if (isInsideResult.cancelled()) {
            Railroad.LOGGER.warn("git {} was cancelled for path: {}", isInsideCmd.argsString(), path);
            return Optional.empty();
        }

        if (isInsideResult.exitCode() != 0 || !"true".equalsIgnoreCase(isInsideResult.readFirstStdoutLine()))
            return Optional.empty();

        GitCommand topLevelCmd = GitCommands.revParseShowTopLevel(path);
        GitResult topLevelResult = runner.run(topLevelCmd, null, null, ResultCaptureMode.TEXT_LINES);
        if (topLevelResult.timedOut()) {
            Railroad.LOGGER.warn("git {} timed out for path: {}", topLevelCmd.argsString(), path);
            return Optional.empty();
        }

        if (topLevelResult.cancelled()) {
            Railroad.LOGGER.warn("git {} was cancelled for path: {}", topLevelCmd.argsString(), path);
            return Optional.empty();
        }

        if (topLevelResult.exitCode() != 0)
            return Optional.empty();

        String topLevelPathStr = String.join("", topLevelResult.stdout()).trim();
        try {
            Path topLevelPath = Path.of(topLevelPathStr).toAbsolutePath().normalize();
            return Optional.of(new GitRepository(topLevelPath));
        } catch (Exception exception) {
            Railroad.LOGGER.warn("Failed to parse git top-level path: {}", topLevelPathStr, exception);
            return Optional.empty();
        }
    }

    public void commitChanges(GitRepository repo, GitCommitData commit, boolean pushAfterCommit) {
        GitCommand commitCmd = GitCommands.commit(repo, commit);
        GitResult commitResult = runner.run(commitCmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (commitResult.timedOut())
            throw new GitExecutionException("git commit timed out");

        if (commitResult.cancelled())
            throw new GitExecutionException("git commit was cancelled");

        if (commitResult.exitCode() != 0)
            throw new GitExecutionException("git commit failed: " + String.join("\n", commitResult.stderr()));

        if (pushAfterCommit) {
            // TODO: Allow passing listeners from higher up
            push(repo, GitOutputListener.NO_OP, event -> {
                if (event instanceof GitProgressEvent.Percentage(String phase, int percent)) {
                    Railroad.LOGGER.debug("Git Push Progress - {}: {}%", phase, percent);
                } else if (event instanceof GitProgressEvent.Message(String message)) {
                    Railroad.LOGGER.debug("Git Push Message - {}", message);
                }
            });
        }
    }

    public List<GitRemote> getRemotes(GitRepository repo) {
        GitCommand cmd = GitCommands.remoteGetUrls(repo);
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git remote timed out");

        if (result.cancelled())
            throw new GitExecutionException("git remote was cancelled");

        if (result.exitCode() != 0)
            throw new GitExecutionException("git remote failed: " + String.join("\n", result.stderr()));

        return GitRemoteParser.parseRemoteUrls(result.stdout());
    }

    public Optional<GitUpstream> getUpstream(GitRepository repo) {
        GitCommand cmd = GitCommands.getUpstream(repo);
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git rev-parse timed out");

        if (result.cancelled())
            throw new GitExecutionException("git rev-parse was cancelled");

        if (result.exitCode() != 0)
            return Optional.empty();

        String upstreamRef = String.join("", result.stdout()).trim();
        if (upstreamRef.isEmpty())
            return Optional.empty();

        String remoteName;
        String branchName;
        if (upstreamRef.contains("/")) {
            String[] parts = upstreamRef.split("/", 2);
            remoteName = parts[0];
            branchName = parts[1];
        } else {
            remoteName = "origin";
            branchName = upstreamRef;
        }

        return Optional.of(new GitUpstream(remoteName, branchName));
    }

    public void fetch(GitRepository repo, GitOutputListener rawListener, Consumer<GitProgressEvent> progressListener) {
        GitCommand cmd = GitCommands.fetch(repo);

        GitOutputListener listener = GitListeners.withProgress(rawListener, progressListener, "Fetch");
        GitResult result = runner.run(cmd, listener, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git fetch timed out");

        if (result.cancelled())
            throw new GitExecutionException("git fetch was cancelled");

        if (result.exitCode() != 0)
            throw new GitExecutionException("git fetch failed: " + String.join("\n", result.stderr()));
    }

    public void push(GitRepository repo, GitOutputListener outputListener, Consumer<GitProgressEvent> progressListener) {
        GitCommand cmd = GitCommands.push(repo);

        GitOutputListener listener = GitListeners.withProgress(outputListener, progressListener, "Push");
        GitResult result = runner.run(cmd, listener, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git push timed out");

        if (result.cancelled())
            throw new GitExecutionException("git push was cancelled");

        if (result.exitCode() != 0)
            throw new GitExecutionException("git push failed: " + String.join("\n", result.stderr()));
    }

    public void pull(GitRepository repo, GitOutputListener outputListener, Consumer<GitProgressEvent> progressListener) {
        GitCommand cmd = GitCommands.pull(repo);

        GitOutputListener listener = GitListeners.withProgress(outputListener, progressListener, "Pull");
        GitResult result = runner.run(cmd, listener, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git pull timed out");

        if (result.cancelled())
            throw new GitExecutionException("git pull was cancelled");

        if (result.exitCode() != 0)
            throw new GitExecutionException("git pull failed: " + String.join("\n", result.stderr()));
    }

    public String getUserName() {
        GitCommand cmd = GitCommands.getUserName();
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git config user.name timed out");

        if (result.cancelled())
            throw new GitExecutionException("git config user.name was cancelled");

        if (result.exitCode() != 0)
            return null;

        String userName = String.join("", result.stdout()).trim();
        return userName.isEmpty() ? null : userName;
    }

    public String getUserEmail() {
        GitCommand cmd = GitCommands.getUserEmail();
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git config user.email timed out");

        if (result.cancelled())
            throw new GitExecutionException("git config user.email was cancelled");

        if (result.exitCode() != 0)
            return null;

        String userEmail = String.join("", result.stdout()).trim();
        return userEmail.isEmpty() ? null : userEmail;
    }

    public String getCommitGpgSignSetting() {
        GitCommand cmd = GitCommands.getCommitGpgSign();
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git config commit.gpgSign timed out");

        if (result.cancelled())
            throw new GitExecutionException("git config commit.gpgSign was cancelled");

        if (result.exitCode() != 0)
            return null;

        String gpgSign = String.join("", result.stdout()).trim();
        return gpgSign.isEmpty() ? null : gpgSign;
    }

    public String getGpgFormatSetting() {
        GitCommand cmd = GitCommands.getGpgFormat();
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git config gpg.format timed out");

        if (result.cancelled())
            throw new GitExecutionException("git config gpg.format was cancelled");

        if (result.exitCode() != 0)
            return null;

        String gpgFormat = String.join("", result.stdout()).trim();
        return gpgFormat.isEmpty() ? null : gpgFormat;
    }

    public String getUserSigningKey() {
        GitCommand cmd = GitCommands.getUserSigningKey();
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git config user.signingkey timed out");

        if (result.cancelled())
            throw new GitExecutionException("git config user.signingkey was cancelled");

        if (result.exitCode() != 0)
            return null;

        String signingKey = String.join("", result.stdout()).trim();
        return signingKey.isEmpty() ? null : signingKey;
    }

    public String getGpgProgramSetting() {
        GitCommand cmd = GitCommands.getGpgProgram();
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git config gpg.program timed out");

        if (result.cancelled())
            throw new GitExecutionException("git config gpg.program was cancelled");

        if (result.exitCode() != 0)
            return null;

        String gpgProgram = String.join("", result.stdout()).trim();
        return gpgProgram.isEmpty() ? null : gpgProgram;
    }

    public String getGitVersion() {
        GitCommand cmd = GitCommands.getGitVersion();
        GitResult result = runner.run(cmd, null, null, ResultCaptureMode.TEXT_LINES);

        if (result.timedOut())
            throw new GitExecutionException("git --version timed out");

        if (result.cancelled())
            throw new GitExecutionException("git --version was cancelled");

        if (result.exitCode() != 0)
            return null;

        String versionLine = String.join("", result.stdout()).trim();
        return versionLine.isEmpty() ? null : versionLine;
    }

    public GitIdentity getIdentity() {
        String userName = getUserName();
        String userEmail = getUserEmail();
        String gpgSignSetting = getCommitGpgSignSetting();
        String gpgFormatSetting = getGpgFormatSetting();
        String userSigningKey = getUserSigningKey();
        String gpgProgram = getGpgProgramSetting();

        SigningStatus signingStatus = SigningStatus.fromGitConfigValues(gpgSignSetting, gpgFormatSetting, userSigningKey, gpgProgram);

        String gitVersion = getGitVersion();

        return new GitIdentity(userName, userEmail, signingStatus, gitVersion);
    }
}
