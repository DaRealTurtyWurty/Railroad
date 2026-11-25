package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.edit.api.WorkspaceEdit;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link RenameProvider#rename}: the edit renaming a symbol and every conflict it would cause.
 *
 * @param oldName current name of the symbol
 * @param newName requested name
 * @param edit snapshot-bound edit performing the rename, or {@code null} when a complete edit cannot be constructed
 * @param conflicts conflicts in the order they were found
 */
public record RenameProposal(
    String oldName,
    String newName,
    @Nullable WorkspaceEdit edit,
    List<RenameConflict> conflicts
) {
    /**
     * Validates a proposal and copies its conflicts.
     *
     * @param oldName current name of the symbol
     * @param newName requested name
     * @param edit edit performing the rename, or {@code null}
     * @param conflicts conflicts found
     * @throws IllegalArgumentException if neither an edit nor an error is supplied
     */
    public RenameProposal {
        Objects.requireNonNull(oldName, "oldName");
        Objects.requireNonNull(newName, "newName");
        conflicts = List.copyOf(conflicts);
        boolean blocked = conflicts.stream()
            .anyMatch(conflict -> conflict.severity() == RenameConflict.Severity.ERROR);
        if (!blocked && edit == null)
            throw new IllegalArgumentException("A rename without error conflicts needs an edit");
    }

    /**
     * Reports whether the rename can be applied without overriding an error.
     *
     * @return {@code true} when the proposal has no error conflicts
     */
    public boolean applicable() {
        return conflicts.stream().noneMatch(conflict -> conflict.severity() == RenameConflict.Severity.ERROR);
    }

    /**
     * Returns the conflicts of one severity.
     *
     * @param severity severity to select
     * @return matching conflicts in order
     */
    public List<RenameConflict> conflicts(RenameConflict.Severity severity) {
        return conflicts.stream().filter(conflict -> conflict.severity() == severity).toList();
    }
}
