package dev.railroadide.railroad.vcs.git.diff;

import java.util.List;

public record DiffHunk(
    int oldStart,
    int oldCount,
    int newStart,
    int newCount,
    String sectionHeader,
    List<DiffHunkLine> lines
) {
}
