package dev.railroadide.railroad.vcs.git;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class GitCommand {
    private final List<String> arguments;
    private final Path workingDirectory;
    private final long timeoutMs;
    private final Map<String, String> environment;
    private final boolean streamStdoutToListener;

    private GitCommand(
        List<String> arguments,
        Path workingDirectory,
        long timeoutMs,
        Map<String, String> environment,
        boolean streamStdoutToListener
    ) {
        this.arguments = List.copyOf(arguments);
        this.workingDirectory = workingDirectory;
        this.timeoutMs = timeoutMs;
        this.environment = Map.copyOf(environment);
        this.streamStdoutToListener = streamStdoutToListener;
    }

    public List<String> arguments() {
        return arguments;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public long timeoutMillis() {
        return timeoutMs;
    }

    public Map<String, String> environment() {
        return environment;
    }

    public boolean streamStdoutToListener() {
        return streamStdoutToListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String argsString() {
        return String.join(" ", arguments);
    }

    public static class Builder {
        private final List<String> arguments = new ArrayList<>();
        private Path workingDirectory = null;
        private long timeoutMs = 0;
        private Map<String, String> environment = new HashMap<>();
        private boolean streamStdoutToListener = false;

        public Builder addArgs(String... args) {
            this.arguments.addAll(Arrays.asList(args));
            return this;
        }

        public Builder workingDirectory(Path path) {
            this.workingDirectory = path;
            return this;
        }

        public Builder workingDirectory(GitRepository repository) {
            this.workingDirectory = repository.root();
            return this;
        }

        public Builder timeout(long duration, TimeUnit unit) {
            this.timeoutMs = unit.toMillis(duration);
            return this;
        }

        public Builder environment(Map<String, String> env) {
            this.environment = env;
            return this;
        }

        public Builder streamStdoutToListener(boolean stream) {
            this.streamStdoutToListener = stream;
            return this;
        }

        public GitCommand build() {
            return new GitCommand(
                this.arguments,
                this.workingDirectory,
                this.timeoutMs,
                this.environment,
                this.streamStdoutToListener
            );
        }
    }
}
