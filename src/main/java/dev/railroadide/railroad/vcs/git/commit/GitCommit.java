package dev.railroadide.railroad.vcs.git.commit;

import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public static @NonNull Map<String, String> parseTagsToCommit(String stdout) {
        Map<String, String> tagToCommit = new HashMap<>();
        for (String line : stdout.split("\n")) {
            int spaceIndex = line.indexOf(' ');
            if (spaceIndex <= 0)
                continue;

            String hash = line.substring(0, spaceIndex);
            String ref = line.substring(spaceIndex + 1);
            if (!ref.startsWith("refs/tags/"))
                continue;

            boolean peeled = ref.endsWith("^{}");
            String tagName = ref.substring("refs/tags/".length(), peeled ? ref.length() - 3 : ref.length());
            if (peeled || !tagToCommit.containsKey(tagName)) {
                tagToCommit.put(tagName, hash);
            }
        }
        return tagToCommit;
    }
}
