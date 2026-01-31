package dev.railroadide.railroad.ide.ui.git.commit.list;

import dev.railroadide.core.ui.*;
import dev.railroadide.core.ui.localized.LocalizedText;
import dev.railroadide.railroad.utility.TimeFormatter;
import dev.railroadide.railroad.vcs.git.GitManager;
import dev.railroadide.railroad.vcs.git.commit.GitCommit;
import io.github.palexdev.materialfx.controls.MFXProgressSpinner;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class GitCommitListViewPane extends RRListView<GitCommit> {
    private static final String PLACEHOLDER_EMPTY_KEY = "railroad.git.commit.list.placeholder.empty";

    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private final ObservableList<GitCommit> allCommits = FXCollections.observableArrayList();
    private final LocalizedText placeholderText = new LocalizedText(PLACEHOLDER_EMPTY_KEY);
    private final MFXProgressSpinner loadingSpinner = new MFXProgressSpinner();
    private volatile List<GitCommit> allCommitsSnapshot = List.of();
    private final Object filterLock = new Object();
    private ScheduledFuture<?> pendingFilterTask;
    private final Map<String, String> commitSearchCache = new HashMap<>();
    private volatile Map<String, List<String>> tagsByCommit = Map.of();
    private volatile String headCommitHash = null;
    private volatile String searchFilter = "";
    private volatile boolean loadingCommits = true;

    public GitCommitListViewPane(GitManager gitManager) {
        super();

        getStyleClass().add("git-commit-list-view-pane");
        enableSmoothScrolling();
        loadingSpinner.getStyleClass().add("git-commit-list-loading-spinner");
        var loadingContainer = new RRStackPane(loadingSpinner);
        loadingContainer.setAlignment(Pos.CENTER);
        setPlaceholder(loadingContainer);
        setCellFactory(param -> new GitCommitListCell());
        gitManager.getCommitListMetadata().thenAccept(metadata -> Platform.runLater(() -> {
            headCommitHash = metadata.headCommitHash();
            tagsByCommit = metadata.tagsByCommit();
            refresh();
        }));
        gitManager.getAllCommits(this::handleCommitsPage, () -> Platform.runLater(this::handleCommitsDone), 200);
    }

    private void handleCommitsPage(List<GitCommit> commits) {
        Platform.runLater(() -> {
            allCommits.addAll(commits);
            allCommitsSnapshot = List.copyOf(allCommits);
            getItems().addAll(commits);
        });
    }

    private void handleCommitsDone() {
        loadingCommits = false;
        setPlaceholder(placeholderText);
        if (hasActiveFilters())
            requestFilterUpdate(0);
    }

    public void setSearchFilter(String searchText) {
        this.searchFilter = searchText != null ? searchText.toLowerCase() : "";
        requestFilterUpdate(150);
    }

    private void requestFilterUpdate(long delayMillis) {
        synchronized (filterLock) {
            if (pendingFilterTask != null) {
                pendingFilterTask.cancel(false);
            }

            pendingFilterTask = executorService.schedule(this::applyFilters, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void applyFilters() {
        if (loadingCommits)
            return;

        if (!hasActiveFilters()) {
            List<GitCommit> commitsSnapshot = allCommitsSnapshot;
            Platform.runLater(() -> getItems().setAll(commitsSnapshot));
            return;
        }

        List<GitCommit> commitsSnapshot = allCommitsSnapshot;
        List<GitCommit> filtered = new ArrayList<>(commitsSnapshot.size());
        String searchText = searchFilter;

        for (GitCommit commit : commitsSnapshot) {
            if (!searchText.isEmpty()) {
                String searchable = commitSearchCache.computeIfAbsent(commit.hash(), hash -> String.join("\n",
                    commit.subject(),
                    commit.authorName(),
                    commit.authorEmail(),
                    commit.hash()
                ).toLowerCase());
                if (!searchable.contains(searchText))
                    continue;
            }

            filtered.add(commit);
        }

        Platform.runLater(() -> getItems().setAll(filtered));
    }

    private boolean hasActiveFilters() {
        return searchFilter != null && !searchFilter.isEmpty();
    }

    private long getCommitTimestampEpochSeconds(GitCommit commit) {
        if (commit == null)
            return 0L;

        long committerSeconds = commit.committerTimestampEpochSeconds();
        return committerSeconds > 0L ? committerSeconds : commit.authorTimestampEpochSeconds();
    }

    private class GitCommitListCell extends ListCell<GitCommit> {
        private final GitCommitCellPane cellPane = new GitCommitCellPane();

        @Override
        protected void updateItem(GitCommit item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                cellPane.clear();
                setText(null);
                setGraphic(null);
            } else {
                cellPane.setCommit(item);
                setGraphic(cellPane);
            }
        }
    }

    private class GitCommitCellPane extends RRHBox {
        private final GitCommitIconPane icon;
        private final GitCommitDetailsPane details;
        private final GitCommitTimestampPane timestamp;

        public GitCommitCellPane() {
            super(4);
            getStyleClass().add("git-commit-cell-pane");

            icon = new GitCommitIconPane();
            icon.getStyleClass().add("git-commit-icon-pane");

            details = new GitCommitDetailsPane();
            details.getStyleClass().add("git-commit-details-pane");

            timestamp = new GitCommitTimestampPane();
            timestamp.getStyleClass().add("git-commit-timestamp-pane");

            var spacer = new Region();
            getChildren().addAll(icon, details, timestamp, spacer);
            HBox.setHgrow(details, Priority.SOMETIMES);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox.setHgrow(timestamp, Priority.NEVER);
        }

        public void setCommit(GitCommit commit) {
            icon.setCommit(commit);
            details.setCommit(commit);
            timestamp.setCommit(commit);
        }

        public void clear() {
            timestamp.clear();
        }
    }

    private static class GitCommitIconPane extends RRVBox {
        private final Circle regularIcon = new Circle(6);
        private final Polygon mergeIcon = new Polygon();
        private final Polygon revertIcon = new Polygon();
        private final Polygon squashIcon = new Polygon();
        private final Polygon initialIcon = new Polygon();

        public GitCommitIconPane() {
            super();
            getStyleClass().add("git-commit-icon-pane");

            regularIcon.getStyleClass().add("git-commit-icon-circle");
            mergeIcon.getStyleClass().add("git-commit-icon-merge");
            revertIcon.getStyleClass().add("git-commit-icon-revert");
            squashIcon.getStyleClass().add("git-commit-icon-squash");
            initialIcon.getStyleClass().add("git-commit-icon-initial");
            mergeIcon.getPoints().addAll(
                6.0, 0.0,
                12.0, 6.0,
                6.0, 12.0,
                0.0, 6.0
            );
            revertIcon.getPoints().addAll(
                6.0, 0.0,
                12.0, 12.0,
                0.0, 12.0
            );
            squashIcon.getPoints().addAll(
                0.0, 0.0,
                12.0, 0.0,
                12.0, 12.0,
                0.0, 12.0
            );
            initialIcon.getPoints().addAll(
                6.0, 0.0,
                11.5, 3.0,
                11.5, 9.0,
                6.0, 12.0,
                0.5, 9.0,
                0.5, 3.0
            );

            getChildren().addAll(regularIcon, mergeIcon, revertIcon, squashIcon, initialIcon);
            setCommit(null);
        }

        public void setCommit(GitCommit commit) {
            String subject = commit == null ? "" : commit.subject();
            String subjectLower = subject == null ? "" : subject.toLowerCase();
            int parentCount = commit != null && commit.parentHashes() != null ? commit.parentHashes().size() : 0;

            boolean isInitial = commit != null && parentCount == 0;
            boolean isMerge = commit != null && parentCount > 1;
            boolean isRevert = commit != null && subjectLower.startsWith("revert");
            boolean isSquash = commit != null && subjectLower.contains("squash");

            boolean showInitial = isInitial;
            boolean showMerge = !showInitial && isMerge;
            boolean showRevert = !showInitial && !showMerge && isRevert;
            boolean showSquash = !showInitial && !showMerge && !showRevert && isSquash;
            boolean showRegular = !showInitial && !showMerge && !showRevert && !showSquash;

            initialIcon.setVisible(showInitial);
            initialIcon.setManaged(showInitial);
            mergeIcon.setVisible(showMerge);
            mergeIcon.setManaged(showMerge);
            revertIcon.setVisible(showRevert);
            revertIcon.setManaged(showRevert);
            squashIcon.setVisible(showSquash);
            squashIcon.setManaged(showSquash);
            regularIcon.setVisible(showRegular);
            regularIcon.setManaged(showRegular);
        }
    }

    private class GitCommitDetailsPane extends RRVBox {
        private final Text message = new Text();
        private final Text author = new Text();
        private final Text hash = new Text();
        private final Text separator = new Text("•");
        private final RRFlowPane tagsFlow = new RRFlowPane(Orientation.HORIZONTAL, 4, 4);
        private final Tooltip authorTooltip = new Tooltip();
        private final Tooltip hashTooltip = new Tooltip();

        public GitCommitDetailsPane() {
            super(2);
            getStyleClass().add("git-commit-details-pane");

            message.getStyleClass().add("git-commit-message");

            var subheadingHBox = new RRHBox(4);
            subheadingHBox.getStyleClass().add("git-commit-subheading-hbox");

            author.getStyleClass().add("git-commit-author");

            hash.getStyleClass().add("git-commit-hash");
            separator.getStyleClass().add("git-commit-separator");

            tagsFlow.getStyleClass().add("git-commit-tags-pane");
            tagsFlow.setPrefWrapLength(220);
            tagsFlow.setMaxWidth(220);
            tagsFlow.setMinWidth(0);

            subheadingHBox.getChildren().addAll(author, hash);
            Tooltip.install(author, authorTooltip);
            Tooltip.install(hash, hashTooltip);

            getChildren().addAll(message, subheadingHBox);
        }

        public void setCommit(GitCommit commit) {
            message.setText(commit.subject().strip());
            author.setText(commit.authorName());
            authorTooltip.setText(commit.authorEmail());
            hash.setText(commit.shortHash());
            hashTooltip.setText(commit.hash());

            tagsFlow.getChildren().clear();
            List<String> tags = new ArrayList<>(tagsByCommit.getOrDefault(commit.hash(), List.of()));
            if (commit.hash().equals(headCommitHash)) {
                tags.add("HEAD");
            }

            var subheading = (RRHBox) getChildren().get(1);
            if (!tags.isEmpty()) {
                if (!subheading.getChildren().contains(separator)) {
                    subheading.getChildren().addAll(separator, tagsFlow);
                }

                for (String tag : tags) {
                    var tagLabel = new Label(tag);
                    tagLabel.getStyleClass().add("git-commit-tag");
                    tagsFlow.getChildren().add(tagLabel);
                }
            } else {
                subheading.getChildren().removeAll(separator, tagsFlow);
            }
        }
    }

    private class GitCommitTimestampPane extends RRVBox {
        private final Text timestampText = new Text();
        private final Tooltip tooltip = new Tooltip();
        private ScheduledFuture<?> updateTask;

        public GitCommitTimestampPane() {
            super();
            getStyleClass().add("git-commit-timestamp-pane");

            timestampText.getStyleClass().add("git-commit-timestamp-text");
            Tooltip.install(timestampText, tooltip);

            getChildren().add(timestampText);
        }

        public void setCommit(GitCommit commit) {
            clear();
            var millis = new AtomicLong(getCommitTimestampEpochSeconds(commit) * 1000L);
            timestampText.setText(TimeFormatter.formatElapsed(millis.get()));
            tooltip.setText(TimeFormatter.formatDateTime(millis.get()));
            updateTask = executorService.scheduleAtFixedRate(() -> {
                millis.set(getCommitTimestampEpochSeconds(commit) * 1000L);
                var formattedTime = TimeFormatter.formatElapsed(millis.get());
                Platform.runLater(() -> {
                    timestampText.setText(formattedTime);
                    tooltip.setText(TimeFormatter.formatDateTime(millis.get()));
                });
            }, 1, 1, TimeUnit.SECONDS);
        }

        public void clear() {
            if (updateTask != null) {
                updateTask.cancel(false);
                updateTask = null;
            }
        }
    }
}
