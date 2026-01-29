package dev.railroadide.railroad.ide.ui.git.overview;

import dev.railroadide.core.ui.RRHBox;
import dev.railroadide.core.ui.RRListView;
import dev.railroadide.core.ui.RRVBox;
import dev.railroadide.core.ui.localized.LocalizedText;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.ShutdownHooks;
import dev.railroadide.railroad.utility.StringUtils;
import dev.railroadide.railroad.vcs.git.CommitPage;
import dev.railroadide.railroad.vcs.git.GitCommit;
import dev.railroadide.railroad.vcs.git.GitManager;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Text;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class GitOverviewRecentCommitsPane extends RRListView<GitCommit> {
    private static final int FALLBACK_COMMIT_COUNT = 5;
    private final AtomicInteger requestedCount = new AtomicInteger(0);

    public GitOverviewRecentCommitsPane(Project project) {
        getStyleClass().add("git-overview-recent-commits-pane");
        setPlaceholder(new LocalizedText("railroad.git.overview.recent_commits.placeholder"));

        GitManager gitManager = project.getGitManager();
        requestCommits(gitManager, FALLBACK_COMMIT_COUNT);
        setCellFactory(listView -> new GitOverviewRecentCommitCell());

        heightProperty().addListener((obs, oldHeight, newHeight) ->
            updateCommitLimitFromHeight(gitManager));
        skinProperty().addListener((obs, oldSkin, newSkin) ->
            Platform.runLater(() -> updateCommitLimitFromHeight(gitManager)));

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "GitOverviewRecentCommitsPane-Commits-Fetcher");
            thread.setDaemon(true);
            return thread;
        });

        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        sceneProperty().addListener((obs, oldScene, newScene) ->
            onSceneChanged(newScene, future, executor, gitManager));

        ShutdownHooks.addHook(executor::shutdownNow);
    }

    private void onSceneChanged(Scene newScene,
                                AtomicReference<ScheduledFuture<?>> futureRef,
                                ScheduledExecutorService executor,
                                GitManager gitManager) {
        ScheduledFuture<?> previousFuture = futureRef.getAndSet(null);
        if (previousFuture != null) {
            previousFuture.cancel(false);
        }

        if (newScene != null) {
            ScheduledFuture<?> newFuture = executor.scheduleAtFixedRate(() ->
                requestCommits(gitManager, Math.max(1, requestedCount.get())), 0, 1, TimeUnit.MINUTES);
            futureRef.set(newFuture);
        }
    }

    private void updateCommitLimitFromHeight(GitManager gitManager) {
        int computed = computeCommitLimit();
        if (computed > 0) {
            requestCommits(gitManager, computed);
        }
    }

    private int computeCommitLimit() {
        double cellHeight = resolveCellHeight();
        if (cellHeight <= 0)
            return -1;

        double availableHeight = getHeight() - snappedTopInset() - snappedBottomInset();
        int count = (int) Math.floor(availableHeight / cellHeight);
        return Math.max(1, count);
    }

    private double resolveCellHeight() {
        double fixed = getFixedCellSize();
        if (fixed > 0)
            return fixed;

        var cell = lookup(".list-cell");
        if (cell != null && cell.getBoundsInParent().getHeight() > 0)
            return cell.getBoundsInParent().getHeight();

        return -1;
    }

    private void requestCommits(GitManager gitManager, int count) {
        int clamped = Math.max(1, count);
        if (requestedCount.getAndSet(clamped) == clamped)
            return;

        gitManager.getRecentCommits(clamped).thenAccept(optCommits ->
            optCommits.map(CommitPage::commits).ifPresent(commits ->
                Platform.runLater(() -> getItems().setAll(commits))));
    }

    private static class GitOverviewRecentCommitCell extends ListCell<GitCommit> {
        @Override
        protected void updateItem(GitCommit item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                setGraphic(new GitOverviewRecentCommitCellPane(item));
            }
        }
    }

    private static class GitOverviewRecentCommitCellPane extends RRHBox {
        public GitOverviewRecentCommitCellPane(GitCommit commit) {
            getStyleClass().add("git-overview-recent-commit-cell-pane");

            var leftVBox = new RRVBox(2);
            var messageLabel = new Text(commit.subject());
            messageLabel.getStyleClass().add("commit-message-label");
            leftVBox.getChildren().add(messageLabel);

            var leftHBox = new RRHBox(5);
            var authorLabel = new Text(commit.authorName());
            authorLabel.getStyleClass().add("commit-author-label");
            Tooltip.install(authorLabel, new Tooltip(commit.authorEmail()));
            leftHBox.getChildren().add(authorLabel);

            var shortHashLabel = new Text(commit.shortHash());
            shortHashLabel.getStyleClass().add("commit-short-hash-label");
            Tooltip.install(shortHashLabel, new Tooltip(commit.hash()));
            leftHBox.getChildren().add(shortHashLabel);

            long timestampEpochSeconds = commit.authorTimestampEpochSeconds();
            long timestampEpochMillis = timestampEpochSeconds * 1000L;
            var timestampLabel = new Text(StringUtils.formatElapsed(timestampEpochMillis));
            timestampLabel.getStyleClass().add("commit-timestamp-label");
            Tooltip.install(timestampLabel, new Tooltip(StringUtils.formatDateTime(timestampEpochMillis)));

            leftVBox.getChildren().add(leftHBox);
            getChildren().add(leftVBox);
            getChildren().add(timestampLabel);
            HBox.setHgrow(leftVBox, Priority.ALWAYS);

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "GitOverviewRecentCommitCellPane-Timestamp-Updater");
                thread.setDaemon(true);
                return thread;
            });

            AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
            sceneProperty().addListener((obs, oldScene, newScene) -> {
                ScheduledFuture<?> previousFuture = futureRef.getAndSet(null);
                if (previousFuture != null) {
                    previousFuture.cancel(false);
                }

                if (newScene != null) {
                    ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
                        Platform.runLater(() -> timestampLabel.setText(StringUtils.formatElapsed(timestampEpochMillis)));
                    }, 1, 1, TimeUnit.SECONDS);
                    futureRef.set(future);
                }
            });

            ShutdownHooks.addHook(executor::shutdownNow);
        }
    }
}
