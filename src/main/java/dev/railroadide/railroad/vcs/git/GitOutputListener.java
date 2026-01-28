package dev.railroadide.railroad.vcs.git;

public interface GitOutputListener {
    GitOutputListener NO_OP = new GitOutputListener() {
        @Override
        public void onStdout(String line) {
            // No-op
        }

        @Override
        public void onStdoutRecord(String record) {
            // No-op
        }

        @Override
        public void onStderr(String line) {
            // No-op
        }
    };

    void onStdout(String line);
    void onStdoutRecord(String record);
    void onStderr(String line);
}
