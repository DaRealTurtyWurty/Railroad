package dev.railroadide.railroad.vcs;

import dev.railroadide.railroad.settings.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class GitManager {
    public static final GitManager INSTANCE = new GitManager();

    private GitManager() {
    }

    public static void loadGitExecutableIntoSettings() {
        Optional<Path> optionalPath = Settings.GIT_EXECUTABLE_PATH.getOptional();
        if (optionalPath.isEmpty()) {
            GitLocator.findGitExecutable().ifPresent(Settings.GIT_EXECUTABLE_PATH::setValue);
        }
    }

    public String getVersion() {
        Optional<Path> optionalPath = Settings.GIT_EXECUTABLE_PATH.getOptional();
        if (optionalPath.isEmpty())
            return "Git not configured";

        CompletableFuture<String> fetchedGitVersion = fetchGitVersion(optionalPath.get());
        try {
            return fetchedGitVersion.get();
        } catch (Exception exception) {
            return "Unknown";
        }
    }

    private static CompletableFuture<Process> executeGitCommand(Path gitExecutable, long timeout, String... args) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            String[] command = buildCommand(gitExecutable, args);

            try {
                Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

                boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("Git command timed out");
                }

                if (process.exitValue() != 0)
                    throw new RuntimeException("Git command failed with exit code " + process.exitValue());

                return process;
            } catch (Exception exception) {
                throw new RuntimeException("Failed to execute git command", exception);
            }
        });
    }

    private static String[] buildCommand(Path gitExecutable, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = gitExecutable.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        return command;
    }

    private static CompletableFuture<String> fetchGitVersion(Path gitExecutable) {
        CompletableFuture<String> future = new CompletableFuture<>();
        new Thread(() -> {
            try {
                Process process = executeGitCommand(
                    gitExecutable,
                    Settings.GIT_VERSION_COMMAND_TIMEOUT_MS.getValue(),
                    "--version"
                ).get();

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null && line.startsWith("git version "))
                        future.complete(line.substring("git version ".length()).trim());
                }
            } catch (Exception ignored) {
            }

            future.complete("Unknown");
        }, "Git Version Fetcher").start();

        return future;
    }
}
