package dev.railroadide.railroad.vcs.git;

import dev.railroadide.railroad.Railroad;

import java.nio.file.Path;
import java.util.Optional;

// TODO: Add small FS cache for detected repositories to avoid repeated git calls
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
}
