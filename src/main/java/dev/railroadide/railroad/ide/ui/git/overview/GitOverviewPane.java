package dev.railroadide.railroad.ide.ui.git.overview;

import dev.railroadide.core.ui.RRVBox;
import dev.railroadide.railroad.project.Project;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class GitOverviewPane extends RRVBox {
    private final GitOverviewHeaderPane headerPane;
    private final GitOverviewIdentityPane identityPane;
    private final GitOverviewRecentCommitsPane recentCommitsPane;

    public GitOverviewPane(Project project) {
        getStyleClass().add("git-overview-pane-root");
        setSpacing(8); // Add some spacing between the components

        this.headerPane = new GitOverviewHeaderPane(project);
        this.identityPane = new GitOverviewIdentityPane(project);
        this.recentCommitsPane = new GitOverviewRecentCommitsPane(project);

        // Add children directly to the VBox
        getChildren().addAll(
            headerPane,
            identityPane,
            recentCommitsPane
        );
        
        // Give the recentCommitsPane vertical grow priority so it expands to fill available space
        VBox.setVgrow(recentCommitsPane, Priority.ALWAYS);
    }
}
