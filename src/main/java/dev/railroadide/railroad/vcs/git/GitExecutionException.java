package dev.railroadide.railroad.vcs.git;

public class GitExecutionException extends RuntimeException {
    public GitExecutionException(String message) {
        super(message);
    }

    public GitExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
