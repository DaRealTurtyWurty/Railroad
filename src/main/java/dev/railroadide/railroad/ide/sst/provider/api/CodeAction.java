package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.document.api.TextDocumentSnapshot;
import dev.railroadide.railroad.plugin.spi.editor.EditResult;
import dev.railroadide.railroad.plugin.spi.editor.TextEditorAccess;
import dev.railroadide.railroad.plugin.spi.editor.TextReplacement;
import java.util.List;
import java.util.Objects;

/**
 * A single-document fix bound to its exact input snapshot. Hosts apply through the existing
 * atomic editor edit boundary, which rejects stale snapshots, overlaps and read-only documents.
 *
 * @param title nonblank label displayed for this action
 * @param expected exact document snapshot against which the edits were computed
 * @param replacements edits to apply atomically, copied into an immutable list
 */
public record CodeAction(String title, TextDocumentSnapshot expected, List<TextReplacement> replacements) {
    /**
     * Creates a snapshot-bound action with an immutable copy of its edits.
     *
     * @param title nonblank label displayed for this action
     * @param expected exact document snapshot against which the edits were computed
     * @param replacements edits to apply atomically, copied into an immutable list
     * @throws IllegalArgumentException if the title is blank
     */
    public CodeAction {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(expected, "expected");
        replacements = List.copyOf(replacements);
        if (title.isBlank())
            throw new IllegalArgumentException("Action title must not be blank");
    }

    /**
     * Applies on the editor UI thread, with the same version checks as every plugin edit.
     *
     * @param editor editor that owns the target document
     * @return the outcome of the atomic edit attempt
     */
    public EditResult apply(TextEditorAccess editor) {
        return editor.replace(expected, replacements);
    }
}
