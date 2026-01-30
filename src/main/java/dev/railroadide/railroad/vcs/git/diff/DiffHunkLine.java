package dev.railroadide.railroad.vcs.git.diff;

import org.jetbrains.annotations.Nullable;

public record DiffHunkLine(
    LineType type,
    @Nullable Integer oldLineNumber,
    @Nullable Integer newLineNumber,
    String content,
    boolean noNewlineAtEnd
) {
    public static DiffHunkLine noNewlineAtEnd(DiffHunkLine line) {
        return new DiffHunkLine(
            line.type(),
            line.oldLineNumber(),
            line.newLineNumber(),
            line.content(),
            true
        );
    }

    public enum LineType {
        CONTEXT,
        ADDITION,
        DELETION
    }
}
