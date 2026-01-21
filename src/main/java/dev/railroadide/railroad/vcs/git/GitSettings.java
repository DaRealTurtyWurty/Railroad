package dev.railroadide.railroad.vcs.git;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public final class GitSettings {
    private Long autoRefreshIntervalMillis;
}
