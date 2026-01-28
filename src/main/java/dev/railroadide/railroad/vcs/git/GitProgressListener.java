package dev.railroadide.railroad.vcs.git;

import java.util.function.Consumer;

public final class GitProgressListener implements GitOutputListener {
    public static final GitProgressListener NO_OP = new GitProgressListener(GitOutputListener.NO_OP, $ -> {}, null);

    private final GitOutputListener raw;
    private final Consumer<GitProgressEvent> sink;

    private volatile String currentPhase;

    public GitProgressListener(GitOutputListener raw, Consumer<GitProgressEvent> sink, String initialPhase) {
        this.raw = raw;
        this.sink = sink;
        this.currentPhase = initialPhase == null ? "(working)" : initialPhase;
    }

    @Override
    public void onStdout(String line) {
        if (raw != null) {
            raw.onStdout(line);
        }

        emitIfProgress(line);
    }

    @Override
    public void onStderr(String line) {
        if (raw != null) {
            raw.onStderr(line);
        }

        emitIfProgress(line);
    }

    @Override
    public void onStdoutRecord(String record) {
        if (raw != null) {
            raw.onStdoutRecord(record);
        }
    }

    private void emitIfProgress(String line) {
        GitProgressParser.tryParse(line, currentPhase).ifPresent(event -> {
            if (event instanceof GitProgressEvent.Phase(String name)) {
                currentPhase = name;
            }

            // TODO: Replace 'ignored' with '_' in java 25
            if (event instanceof GitProgressEvent.Percentage(String phase, int ignored)) {
                currentPhase = phase;
            }

            if (sink != null) {
                sink.accept(event);
            }
        });
    }
}
