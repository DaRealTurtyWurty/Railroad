package dev.railroadide.railroad.vcs.git.commit;

import java.util.List;
import java.util.Map;

public record CommitListMetadata(String headCommitHash, Map<String, List<String>> tagsByCommit) {
}
