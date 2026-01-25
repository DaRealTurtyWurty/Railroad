package dev.railroadide.railroad.ide.ui.git.changes;

import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.vcs.git.FileChange;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import org.jspecify.annotations.NonNull;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.StringJoiner;
import java.util.function.Consumer;

public record FileItem(Project project, FileChange change) implements ChangeItem {
    @Override
    public Node getIcon() {
        // TODO: Replace with some icon manager lookup
        var fontIcon = new FontIcon(FontAwesomeSolid.FILE);
        fontIcon.getStyleClass().add("git-file-icon");
        fontIcon.setIconSize(16);
        return fontIcon;
    }

    @Override
    public String getTitle() {
        return change.path().getFileName().toString();
    }

    @Override
    public String getSubtitle() {
        return "";
    }

    @Override
    public ContextMenu getContextMenu(Project project) {
        return null; // TODO: Implement context menu
    }

    @Override
    public Consumer<Boolean> getSelectionHandler() {
        return isSelected -> {

        };
    }

    @Override
    public Consumer<ActionEvent> getDoubleClickHandler() {
        return event -> {

        };
    }

    @Override
    public String getStyleClass() {
        var joiner = new StringJoiner(" ");
        joiner.add("git-file-item");

        String suffix = "";
        if (change.isAdded()) {
            suffix = "-added";
        } else if (change.isDeleted()) {
            suffix = "-deleted";
        } else if (change.isRenamed()) {
            suffix = "-renamed";
        } else if (change.isCopied()) {
            suffix = "-copied";
        } else if (change.isConflict()) {
            suffix = "-unmerged";
        } else if (change.isUntracked()) {
            suffix = "-untracked";
        } else if (change.isModified()) {
            suffix = "-modified";
        }

        if (!suffix.isEmpty()) {
            joiner.add("git-file-item" + suffix);
        }

        return joiner.toString();
    }

    @Override
    public @NonNull String toString() {
        return ChangeItem.formatTitle(getTitle(), getSubtitle());
    }
}
