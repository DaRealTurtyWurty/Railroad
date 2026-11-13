package dev.railroadide.railroad.ide.completion;

import java.util.Collections;
import java.util.List;

/**
 * Immutable completion candidates and the start of the text replaced by a selected candidate.
 *
 * @param replacementStart inclusive UTF-16 replacement offset; the live caret supplies the exclusive end,
 *            or -1 when there are no candidates
 * @param items completion candidates
 */
public record CompletionResult(int replacementStart, List<CompletionItem> items) {
    public CompletionResult {
        items = List.copyOf(items);
        if (replacementStart < -1 || replacementStart == -1 && !items.isEmpty())
            throw new IllegalArgumentException("Completion replacement start must be nonnegative for candidates");
    }
    /**
     * Creates a completion result without a trigger or candidates.
     *
     * @return empty result with trigger offset -1
     */
    public static CompletionResult empty() {
        return new CompletionResult(-1, Collections.emptyList());
    }
}
