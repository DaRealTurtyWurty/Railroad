package dev.railroadide.railroad.vcs.git;

import java.nio.file.Path;

public final class GitFileChangeParser {
    private GitFileChangeParser() {
    }

    public static FileChange parsePorcelainV1ZRecord(GitRepository repo, String record, String nextRecord) {
        if (record == null || record.isEmpty())
            return null;

        if (record.length() < 3)
            return null;

        char x = record.charAt(0);
        char y = record.charAt(1);

        String filePath = record.substring(2).trim();

        // Rename or Copy
        boolean expectsSecondPath = (x == 'R' || x == 'C' || y == 'R' || y == 'C');
        Path repoRoot = repo.root();
        if (expectsSecondPath && nextRecord != null && !nextRecord.isEmpty()) {
            return new FileChange(
                repoRoot.resolve(nextRecord).normalize(),
                repoRoot.resolve(filePath).normalize(),
                x, y
            );
        }

        return new FileChange(
            repoRoot.resolve(filePath).normalize(),
            x, y
        );
    }
}
