package dev.railroadide.railroad.ide.ui.git.commit.changes;

import com.panemu.tiwulfx.control.dock.DetachableTabPane;
import dev.railroadide.core.ui.RRCheckBoxTreeCell;
import dev.railroadide.core.ui.RRHBox;
import dev.railroadide.core.ui.RRStackPane;
import dev.railroadide.railroad.ide.IDESetup;
import dev.railroadide.railroad.ide.ui.git.diff.GitDiffPane;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Tab;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class CommitChangeTreeCell extends RRCheckBoxTreeCell<ChangeItem> {
    private final RRHBox container = new RRHBox(8);
    private final RRStackPane iconContainer = new RRStackPane();
    private final RRHBox textContainer = new RRHBox(2);
    private final Text titleText = new Text();
    private final Text subtitleText = new Text();

    public CommitChangeTreeCell() {
        container.getStyleClass().add("git-commit-change-tree-cell");
        iconContainer.getStyleClass().add("git-commit-change-icon-container");

        titleText.getStyleClass().add("git-commit-change-title-text");
        subtitleText.getStyleClass().add("git-commit-change-subtitle-text");
        textContainer.getChildren().addAll(titleText, subtitleText);

        container.getChildren().addAll(iconContainer, textContainer);
    }

    @Override
    protected void updateItem(ChangeItem item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setGraphic(null);
            setText(null);
            iconContainer.getChildren().clear();
            setOnMouseClicked(null);
        } else {
            if (!iconContainer.getChildren().isEmpty()) {
                Node oldIcon = iconContainer.getChildren().getFirst();
                if (!Objects.equals(oldIcon, item.getIcon())) {
                    iconContainer.getChildren().clear();
                    if (item.getIcon() != null) {
                        iconContainer.getChildren().add(item.getIcon());
                    }
                }
            } else {
                if (item.getIcon() != null) {
                    iconContainer.getChildren().add(item.getIcon());
                    if (!container.getChildren().contains(iconContainer)) {
                        container.getChildren().addFirst(iconContainer);
                    }
                } else {
                    container.getChildren().remove(iconContainer);
                }
            }

            titleText.setText(item.getTitle());
            String subtitle = item.getSubtitle();
            if (subtitle != null && !subtitle.isEmpty()) {
                subtitleText.setText(subtitle);
                subtitleText.setVisible(true);
                if (!textContainer.getChildren().contains(subtitleText)) {
                    textContainer.getChildren().add(subtitleText);
                }
            } else {
                subtitleText.setText("");
                subtitleText.setVisible(false);
                textContainer.getChildren().remove(subtitleText);
            }

            textContainer.getStyleClass().clear();
            textContainer.getStyleClass().add("git-commit-change-text-container");
            textContainer.getStyleClass().addAll(item.getStyleClass().split(" "));

            setCustomContent(container);

            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !event.isConsumed() && item instanceof FileItem fileItem) {
                    openDiffForFile(fileItem);
                    event.consume();
                }
            });
        }
    }

    private void openDiffForFile(FileItem fileItem) {
        if (getScene() == null || getScene().getRoot() == null)
            return;

        Parent root = getScene().getRoot();
        Optional<DiffTabLocation> existing = findExistingDiffTab(root);
        DetachableTabPane tabPane = existing.map(DiffTabLocation::tabPane)
            .or(() -> IDESetup.findBestPaneForFiles(root))
            .orElse(null);
        if (tabPane == null)
            return;

        Tab diffTab = existing.map(DiffTabLocation::tab).orElseGet(() -> {
            GitDiffPane diffPane = new GitDiffPane(fileItem.project());
            Tab created = tabPane.addTab("Git Diff", diffPane);
            created.textProperty().bind(diffPane.titleProperty());
            return created;
        });

        tabPane.getSelectionModel().select(diffTab);
        GitDiffPane diffPane = (GitDiffPane) diffTab.getContent();
        if (!diffTab.textProperty().isBound()) {
            diffTab.textProperty().bind(diffPane.titleProperty());
        }
        diffPane.setFilePath(fileItem.change().path());
    }

    private Optional<DiffTabLocation> findExistingDiffTab(Parent parent) {
        for (DetachableTabPane pane : collectTabPanes(parent)) {
            Optional<Tab> diffTab = pane.getTabs().stream()
                .filter(tab -> tab.getContent() instanceof GitDiffPane)
                .findFirst();
            if (diffTab.isPresent())
                return Optional.of(new DiffTabLocation(pane, diffTab.get()));
        }

        return Optional.empty();
    }

    private List<DetachableTabPane> collectTabPanes(Parent parent) {
        List<DetachableTabPane> panes = new ArrayList<>();
        if (parent instanceof DetachableTabPane tabPane) {
            panes.add(tabPane);
        }

        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Parent childParent) {
                panes.addAll(collectTabPanes(childParent));
            }
        }

        return panes;
    }

    private record DiffTabLocation(DetachableTabPane tabPane, Tab tab) {
    }
}
