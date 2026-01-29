package dev.railroadide.railroad.vcs.git.execution;

import java.time.Duration;
import java.util.List;

public record GitResult(int exitCode, List<String> stdout, List<String> stderr, boolean timedOut, boolean cancelled, Duration duration) {
    public String readFirstStdoutLine() {
        if (stdout.isEmpty())
            return "";

        return stdout.getFirst();
    }

    public String readFirstStderrLine() {
        if (stderr.isEmpty())
            return "";

        return stderr.getFirst();
    }

    public String readAllStdout() {
        return String.join("\n", stdout);
    }
}
