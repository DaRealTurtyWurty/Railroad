package dev.railroadide.railroad.vcs.git.commit;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public record GitCommit(
    String hash,
    String shortHash,
    String subject,
    String authorName,
    String authorEmail,
    long authorTimestampEpochSeconds,

    String committerName,
    String committerEmail,
    long committerTimestampEpochSeconds,

    List<String> parentHashes,
    @Nullable String body
) {
    public GitCommit(String hash, String shortHash, String subject, String authorName, String authorEmail, long authorTimestamp, String... parentHashes) {
        this(
            hash,
            shortHash,
            subject,
            authorName,
            authorEmail,
            authorTimestamp,
            null,
            null,
            0L,
            List.of(parentHashes),
            null
        );
    }
}
