package dev.railroadide.railroad.vcs.git.execution.progress;

public interface GitCancellationToken {
    boolean isCancellationRequested();
}
