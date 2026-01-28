package dev.railroadide.railroad.vcs.git;

import java.util.function.Consumer;

public final class GitListeners {
    private GitListeners() {
    }

    public static GitOutputListener withProgress(
        GitOutputListener raw,
        Consumer<GitProgressEvent> sink,
        String defaultPhase
    ) {
        return new GitProgressListener(raw, sink, defaultPhase);
    }
}
