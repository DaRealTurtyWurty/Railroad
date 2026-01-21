package dev.railroadide.railroad.ide.ui.git;

import dev.railroadide.railroad.localization.L18n;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.vcs.git.FileChange;
import io.github.palexdev.materialfx.controls.MFXCheckTreeItem;
import io.github.palexdev.materialfx.controls.MFXCheckTreeView;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class GitCommitChangesPane extends MFXCheckTreeView<GitCommitChangesPane.Item> {
    public GitCommitChangesPane() {
        setShowRoot(false);
        getStyleClass().add("git-commit-changes-pane");


    }

    private void setProjectChanges(Project project, List<FileChange> changes) {

    }

    private void setChanges(List<Item> items) {
        var root = new MFXCheckTreeItem<Item>(null);
        for (var item : items) {
            var treeItem = new TreeItem(item);
            root.getItems().add(treeItem);
        }

        setRoot(root);
    }

    public static class TreeItem extends MFXCheckTreeItem<Item> {
        public TreeItem(Item item) {
            super(item);
            getStyleClass().add(item.getStyleClass());
        }
    }

    public sealed interface Item {
        Node getIcon();

        String getTitle();

        String getSubtitle();

        ContextMenu getContextMenu(Project project);

        Consumer<Boolean> getSelectionHandler();

        Consumer<ActionEvent> getDoubleClickHandler();

        String getStyleClass();
    }

    public record FileItem(Project project, FileChange change) implements Item {
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
            return project.getGitManager().getGitRepository().root().relativize(change.path()).toString();
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
            return "git-file-item";
        }
    }

    public record DirectoryItem(Project project, Path path, List<FileChange> changes) implements Item {
        @Override
        public Node getIcon() {
            // TODO: Replace with some icon manager lookup
            var fontIcon = new FontIcon(FontAwesomeSolid.FOLDER);
            fontIcon.getStyleClass().add("git-directory-icon");
            fontIcon.setIconSize(16);
            return fontIcon;
        }

        @Override
        public String getTitle() {
            return path.getFileName().toString();
        }

        @Override
        public String getSubtitle() {
            return L18n.localize("git.commit.changes.directory.subtitle", String.valueOf(changes.size()));
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
            return "git-directory-item";
        }
    }
}
