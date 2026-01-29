package dev.railroadide.railroad.vcs.git;

import dev.railroadide.railroad.vcs.git.execution.GitOutputListener;
import dev.railroadide.railroad.vcs.git.execution.progress.GitProgressEvent;
import dev.railroadide.railroad.vcs.git.execution.progress.GitProgressListener;

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
