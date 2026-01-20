package dev.railroadide.railroad.vcs.git;

public interface GitOutputListener {
    void onStdout(String line);
    void onStdoutRecord(String record);
    void onStderr(String line);
}
