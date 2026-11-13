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
 */
public record CodeAction(String title, TextDocumentSnapshot expected, List<TextReplacement> replacements) {
    public CodeAction {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(expected, "expected");
        replacements = List.copyOf(replacements);
        if (title.isBlank())
            throw new IllegalArgumentException("Action title must not be blank");
    }

    /** Applies on the editor UI thread, with the same version checks as every plugin edit. */
    public EditResult apply(TextEditorAccess editor) {
        return editor.replace(expected, replacements);
    }
}
