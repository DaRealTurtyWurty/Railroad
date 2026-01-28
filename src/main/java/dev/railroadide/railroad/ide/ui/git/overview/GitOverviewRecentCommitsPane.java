package dev.railroadide.railroad.ide.ui.git.overview;

import dev.railroadide.core.ui.RRListView;
import dev.railroadide.core.ui.localized.LocalizedText;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.vcs.git.GitCommit;

public class GitOverviewRecentCommitsPane extends RRListView<GitCommit> {
    public GitOverviewRecentCommitsPane(Project project) {
        getStyleClass().add("git-overview-recent-commits-pane");
        setPlaceholder(new LocalizedText("railroad.git.overview.recent_commits.placeholder"));


    }
}
