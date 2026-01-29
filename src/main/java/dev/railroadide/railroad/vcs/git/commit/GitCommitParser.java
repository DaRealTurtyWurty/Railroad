package dev.railroadide.railroad.vcs.git.commit;

import dev.railroadide.railroad.Railroad;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GitCommitParser {
    private GitCommitParser() {
    }

    public static GitCommitPage parseCommits(String content, int limit) {
        if (content == null || content.isBlank())
            return new GitCommitPage(Collections.emptyList(), null);

        content = content.trim();
        String[] commits = content.split("\u001E");
        List<GitCommit> commitList = new ArrayList<>();
        for (String commit : commits) {
            if (commit.isBlank())
                continue;

            String[] fields = commit.split("\u0000", -1);
            if (fields.length < 7) {
                Railroad.LOGGER.warn("Malformed git commit entry: {}", commit);
                continue;
            }

            try {
                commitList.add(parseCommit(fields));
            } catch (Exception exception) {
                Railroad.LOGGER.warn("Failed to parse git commit entry: {}", commit, exception);
            }
        }

        String nextCursor = (commitList.size() == limit) ? commitList.getLast().hash() : null;
        return new GitCommitPage(commitList, nextCursor);
    }

    private static @NonNull GitCommit parseCommit(String[] fields) {
        String hash = fields[0];
        if (hash.isBlank())
            throw new IllegalArgumentException("Commit hash cannot be blank");

        String shortHash = fields[1];
        if (shortHash.isBlank()) {
            shortHash = hash.length() >= 7 ? hash.substring(0, 7) : hash;
        }

        String subject = fields[2];

        String authorName = fields[3];
        String authorEmail = fields[4];
        long authorTimestamp = Long.parseLong(fields[5]);

        String parentHashesRaw = fields[6];
        String[] parentHashes = parentHashesRaw.isBlank() ? new String[0] : parentHashesRaw.split("\\s+");

        return new GitCommit(hash, shortHash, subject, authorName, authorEmail, authorTimestamp, parentHashes);
    }
}
