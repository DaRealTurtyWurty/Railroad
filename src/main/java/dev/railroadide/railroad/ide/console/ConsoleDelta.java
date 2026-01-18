package dev.railroadide.railroad.ide.console;

public sealed interface ConsoleDelta {
    record None() implements ConsoleDelta {
    }

    record LinesChanged(int fromLine, int toLine) implements ConsoleDelta {
    }

    record Cleared() implements ConsoleDelta {
    }

    record AppendedLines(int fromIndex, int count) implements ConsoleDelta {
    }

    record ResetAll() implements ConsoleDelta {
    }
}
