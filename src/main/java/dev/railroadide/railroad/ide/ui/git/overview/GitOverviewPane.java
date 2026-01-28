package dev.railroadide.railroad.ide.ui.git.overview;

import dev.railroadide.railroad.project.Project;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.control.SplitPane;

public class GitOverviewPane extends SplitPane {
    private final GitOverviewHeaderPane headerPane;
    private final GitOverviewIdentityPane identityPane;
    private final GitOverviewRecentCommitsPane recentCommitsPane;
    private boolean dividerUpdateScheduled = false;

    public GitOverviewPane(Project project) {
        getStyleClass().add("git-overview-pane-root");
        setOrientation(Orientation.VERTICAL);

        this.headerPane = new GitOverviewHeaderPane(project);
        this.identityPane = new GitOverviewIdentityPane(project);
        this.recentCommitsPane = new GitOverviewRecentCommitsPane(project);

        getItems().addAll(
            headerPane,
            identityPane,
            recentCommitsPane
        );
        SplitPane.setResizableWithParent(headerPane, false);
        SplitPane.setResizableWithParent(identityPane, false);
        SplitPane.setResizableWithParent(recentCommitsPane, true);

        sceneProperty().addListener((obs, oldScene, newScene) -> scheduleDividerUpdate());
        heightProperty().addListener((obs, oldHeight, newHeight) -> scheduleDividerUpdate());
        headerPane.heightProperty().addListener((obs, oldHeight, newHeight) -> scheduleDividerUpdate());
        identityPane.heightProperty().addListener((obs, oldHeight, newHeight) -> scheduleDividerUpdate());
    }

    private void scheduleDividerUpdate() {
        if (dividerUpdateScheduled)
            return;
        
        dividerUpdateScheduled = true;
        Platform.runLater(() -> {
            dividerUpdateScheduled = false;
            updateDividerPositions();
        });
    }

    private void updateDividerPositions() {
        double totalHeight = getHeight();
        if (totalHeight <= 0)
            return;

        double headerHeight = headerPane.prefHeight(-1);
        double identityHeight = identityPane.prefHeight(-1);
        if (headerHeight <= 0 || identityHeight <= 0)
            return;

        double first = Math.clamp(headerHeight / totalHeight, 0.05, 0.9);
        double second = Math.clamp((headerHeight + identityHeight) / totalHeight, first + 0.05, 0.95);
        setDividerPositions(first, second);
    }
}
