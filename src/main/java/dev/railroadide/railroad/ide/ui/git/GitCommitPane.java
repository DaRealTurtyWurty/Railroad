package dev.railroadide.railroad.ide.ui.git;

import dev.railroadide.core.ui.RRVBox;
import dev.railroadide.railroad.project.Project;
import lombok.Getter;

public class GitCommitPane extends RRVBox {
    private final GitCommitHeaderPane gitCommitHeader;
    private final GitCommitChangesPane gitCommitChanges;
    private final GitCommitActionsPane gitCommitActions;

    @Getter
    private final Project project;

    public GitCommitPane(Project project) {
        this.project = project;

        this.gitCommitChanges = new GitCommitChangesPane(project);
        this.gitCommitHeader = new GitCommitHeaderPane(project, gitCommitChanges);
        this.gitCommitActions = new GitCommitActionsPane(project, gitCommitChanges);

        getChildren().addAll(
            gitCommitHeader,
            gitCommitChanges,
            gitCommitActions
        );
    }
}
