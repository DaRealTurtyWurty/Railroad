package dev.railroadide.railroad.ide.ui.git.overview;

import dev.railroadide.core.ui.RRButton;
import dev.railroadide.core.ui.RRHBox;
import dev.railroadide.core.ui.RRTableView;
import dev.railroadide.core.ui.RRVBox;
import dev.railroadide.core.ui.localized.LocalizedText;
import dev.railroadide.core.ui.styling.ButtonVariant;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.ShutdownHooks;
import dev.railroadide.railroad.utility.StringUtils;
import dev.railroadide.railroad.vcs.git.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.beans.binding.Bindings;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// Git Overview Header Pane
// Fetch, Pull, Push Buttons
// Repository - <name> - <dirty>
// HEAD - <branch> - upstream: <remote>/<branch>
// Upstream - <commits ahead/behind> - last fetch: <time>
// Remote - <name> - URL: <url>
// Local Changes - <count> - staged: <count>, unstaged: <count>, untracked: <count>, conflicted: <count>
public class GitOverviewHeaderPane extends RRVBox {
    private final RRHBox actionsBox = new RRHBox(8);
    private final RRTableView<InfoRow> infoTable = new RRTableView<>();
    private final RRHBox changesRow = new RRHBox(10);
    private final ChangeChip stagedChip = new ChangeChip("Staged");
    private final ChangeChip unstagedChip = new ChangeChip("Unstaged");
    private final ChangeChip untrackedChip = new ChangeChip("Untracked");
    private final ChangeChip conflictedChip = new ChangeChip("Conflicted");

    private final InfoRow repositoryRow = new InfoRow("railroad.git.overview.header.repository.label", RowType.REPOSITORY);
    private final InfoRow headRow = new InfoRow("railroad.git.overview.header.head.label", RowType.HEAD);
    private final InfoRow upstreamRow = new InfoRow("railroad.git.overview.header.upstream.label", RowType.UPSTREAM);
    private final InfoRow remoteRow = new InfoRow("railroad.git.overview.header.remote.label", RowType.REMOTE);

    public GitOverviewHeaderPane(Project project) {
        getStyleClass().add("git-overview-header-pane");

        // Actions Box
        actionsBox.getStyleClass().add("git-overview-actions-box");
        actionsBox.setAlignment(Pos.CENTER);
        var fetchButton = new RRButton("railroad.git.overview.header.fetch.button", FontAwesomeSolid.SYNC_ALT);
        fetchButton.setVariant(ButtonVariant.PRIMARY);
        fetchButton.setOnAction($ -> project.getGitManager().fetch());
        actionsBox.getChildren().add(fetchButton);

        var pullButton = new RRButton("railroad.git.overview.header.pull.button", FontAwesomeSolid.DOWNLOAD);
        pullButton.setVariant(ButtonVariant.PRIMARY);
        pullButton.setOnAction($ -> project.getGitManager().pull());
        actionsBox.getChildren().add(pullButton);

        var pushButton = new RRButton("railroad.git.overview.header.push.button", FontAwesomeSolid.PAPER_PLANE);
        pushButton.setVariant(ButtonVariant.PRIMARY);
        pushButton.setOnAction($ -> project.getGitManager().push());
        actionsBox.getChildren().add(pushButton);

        getChildren().add(actionsBox);

        configureTable();
        VBox.setVgrow(infoTable, Priority.ALWAYS);
        getChildren().add(infoTable);
        configureChangeChips();
        getChildren().add(changesRow);

        GitManager gitManager = project.getGitManager();
        updateHeaderInfo(gitManager);
        listenForUpdates(gitManager);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "GitOverviewHeaderPane-Fetch-Thread");
            thread.setDaemon(true);
            return thread;
        });

        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        sceneProperty().addListener((obs, oldScene, newScene) ->
            onSceneChanged(newScene, future, executor, gitManager));

        ShutdownHooks.addHook(executor::shutdownNow);
    }

    private void onSceneChanged(Scene newScene, AtomicReference<ScheduledFuture<?>> future, ScheduledExecutorService executor, GitManager gitManager) {
        if (newScene == null && future.get() != null) {
            future.get().cancel(true);
        } else if (newScene != null) {
            future.set(executor.scheduleAtFixedRate(() ->
                    Platform.runLater(() ->
                        updateUpstreamRow(gitManager)),
                1, 1, TimeUnit.SECONDS));
        } else {
            if (future.get() != null) {
                future.get().cancel(true);
            }
        }
    }

    private void configureTable() {
        infoTable.getStyleClass().addAll("git-overview-info-table", "no-header");
        infoTable.setEditable(false);
        infoTable.setFocusTraversable(false);
        infoTable.setSelectionModel(null);
        infoTable.setPrefWidth(Double.MAX_VALUE);
        infoTable.setMaxWidth(Double.MAX_VALUE);
        infoTable.setFixedCellSize(28);
        infoTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        var labelColumn = new TableColumn<InfoRow, String>();
        labelColumn.setSortable(false);
        labelColumn.setReorderable(false);
        labelColumn.setResizable(false);
        labelColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().labelKey()));
        labelColumn.setCellFactory(column -> new InfoRowLabelTableCell());
        labelColumn.setMinWidth(150);

        var valueColumn = new TableColumn<InfoRow, InfoRow>();
        valueColumn.setSortable(false);
        valueColumn.setReorderable(false);
        valueColumn.setResizable(true);
        valueColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        valueColumn.setCellFactory(column -> new InfoRowValueTableCell());
        valueColumn.prefWidthProperty().bind(infoTable.widthProperty().subtract(labelColumn.widthProperty()).subtract(8));

        // noinspection unchecked
        infoTable.getColumns().addAll(labelColumn, valueColumn);

        ObservableList<InfoRow> rows = FXCollections.observableArrayList(
            repositoryRow,
            headRow,
            upstreamRow,
            remoteRow
        );
        infoTable.setItems(rows);
        infoTable.prefHeightProperty().bind(Bindings.size(infoTable.getItems())
            .multiply(infoTable.fixedCellSizeProperty()).add(2));
        infoTable.maxHeightProperty().bind(infoTable.prefHeightProperty());
    }

    private void updateHeaderInfo(GitManager gitManager) {
        RepoStatus status = gitManager.getRepoStatus();
        List<FileChange> changes = status.changes();
        boolean dirty = !changes.isEmpty();

        String repositoryName = gitManager.getGitRepository().root().getFileName().toString();
        repositoryRow.setPrimary(repositoryName);
        repositoryRow.setSecondary(dirty ? "Dirty" : "Clean");

        GitUpstream upstream = gitManager.getUpstream().orElse(null);

        String upstreamTarget = upstream != null ? upstream.remoteName() + "/" + upstream.branchName() : "None";
        headRow.setPrimary(status.branch());
        headRow.setSecondary(upstreamTarget);

        updateUpstreamRow(gitManager);

        String remoteName = upstream != null ? upstream.remoteName() : "None";
        String remoteUrl = gitManager.getRemotes().stream()
            .filter(r -> upstream != null && r.name().equals(upstream.remoteName()))
            .findFirst()
            .map(GitRemote::fetchUrl)
            .orElse("N/A");
        remoteRow.setPrimary(remoteName);
        remoteRow.setSecondary(remoteUrl);

        long staged = changes.stream().filter(FileChange::isStaged).count();
        long unstaged = changes.stream().filter(FileChange::isUnstaged).count();
        long untracked = changes.stream().filter(FileChange::isUntracked).count();
        long conflicted = changes.stream().filter(FileChange::isConflict).count();
        stagedChip.setCount(staged);
        unstagedChip.setCount(unstaged);
        untrackedChip.setCount(untracked);
        conflictedChip.setCount(conflicted);
        infoTable.refresh();
    }

    private void listenForUpdates(GitManager gitManager) {
        gitManager.repoStatusProperty().addListener((obs, oldStatus, newStatus) ->
            updateHeaderInfo(gitManager));
    }

    private void updateUpstreamRow(GitManager gitManager) {
        var status = gitManager.getRepoStatus();
        upstreamRow.setPrimary(Long.toString(status.behind()));
        upstreamRow.setSecondary(Long.toString(status.ahead()));
        upstreamRow.setTertiary(StringUtils.formatElapsed(gitManager.getLastFetchTimestamp()));
    }

    private void configureChangeChips() {
        changesRow.getStyleClass().add("git-overview-changes-row");
        changesRow.getChildren().addAll(stagedChip, unstagedChip, untrackedChip, conflictedChip);
    }

    private static final class InfoRow {
        private final String labelKey;
        private final RowType type;
        private final StringProperty primary = new SimpleStringProperty("");
        private final StringProperty secondary = new SimpleStringProperty("");
        private final StringProperty tertiary = new SimpleStringProperty("");

        private InfoRow(String labelKey, RowType type) {
            this.labelKey = labelKey;
            this.type = type;
        }

        private String labelKey() {
            return labelKey;
        }

        private RowType type() {
            return type;
        }

        private StringProperty primaryProperty() {
            return primary;
        }

        private StringProperty secondaryProperty() {
            return secondary;
        }

        private StringProperty tertiaryProperty() {
            return tertiary;
        }

        private void setPrimary(String value) {
            this.primary.set(value);
        }

        private void setSecondary(String value) {
            this.secondary.set(value);
        }

        private void setTertiary(String value) {
            this.tertiary.set(value);
        }
    }

    private enum RowType {
        REPOSITORY,
        HEAD,
        UPSTREAM,
        REMOTE
    }

    private static class InfoRowValueTableCell extends TableCell<InfoRow, InfoRow> {
        private final HBox container = new HBox(6);

        private final Text repoNameText = new Text();
        private final HBox repoStatusTag = createTag("git-overview-tag");
        private final Text repoStatusText = new Text();

        private final Text headBranchText = new Text();
        private final HBox headUpstreamTag = createTag("git-overview-tag");
        private final Text headUpstreamLabel = new Text("upstream:");
        private final Text headUpstreamText = new Text();

        private final HBox upstreamBehindTag = createTag("git-overview-tag", "git-overview-tag-warn");
        private final Text upstreamBehindLabel = new Text("behind");
        private final Text upstreamBehindText = new Text();
        private final HBox upstreamAheadTag = createTag("git-overview-tag", "git-overview-tag-good");
        private final Text upstreamAheadLabel = new Text("ahead");
        private final Text upstreamAheadText = new Text();
        private final HBox upstreamFetchTag = createTag("git-overview-tag");
        private final Text upstreamFetchLabel = new Text("last fetch");
        private final Text upstreamFetchText = new Text();

        private final Text remoteNameText = new Text();
        private final Text remoteSeparatorText = new Text("•");
        private final Text remoteUrlText = new Text();

        private InfoRowValueTableCell() {
            container.getStyleClass().add("git-overview-value-container");
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            repoNameText.getStyleClass().addAll("git-overview-table-value-text", "git-overview-value-strong");
            repoStatusText.getStyleClass().add("git-overview-table-value-text");
            repoStatusTag.getChildren().addAll(createDot(), repoStatusText);

            headBranchText.getStyleClass().addAll("git-overview-table-value-text", "git-overview-mono");
            headUpstreamLabel.getStyleClass().add("git-overview-table-value-text");
            headUpstreamText.getStyleClass().addAll("git-overview-table-value-text", "git-overview-mono");
            headUpstreamTag.getChildren().addAll(headUpstreamLabel, headUpstreamText);

            upstreamBehindText.getStyleClass().add("git-overview-table-value-text");
            upstreamBehindText.getStyleClass().add("git-overview-value-strong");
            upstreamBehindLabel.getStyleClass().add("git-overview-table-value-text");
            upstreamBehindTag.getChildren().addAll(createDot("git-overview-dot-warn"), upstreamBehindLabel, upstreamBehindText);
            upstreamAheadText.getStyleClass().add("git-overview-table-value-text");
            upstreamAheadText.getStyleClass().add("git-overview-value-strong");
            upstreamAheadLabel.getStyleClass().add("git-overview-table-value-text");
            upstreamAheadTag.getChildren().addAll(createDot("git-overview-dot-good"), upstreamAheadLabel, upstreamAheadText);
            upstreamFetchText.getStyleClass().addAll("git-overview-table-value-text", "git-overview-mono");
            upstreamFetchLabel.getStyleClass().add("git-overview-table-value-text");
            upstreamFetchTag.getChildren().addAll(upstreamFetchLabel, upstreamFetchText);

            remoteNameText.getStyleClass().addAll("git-overview-table-value-text", "git-overview-mono");
            remoteSeparatorText.getStyleClass().add("git-overview-separator");
            remoteUrlText.getStyleClass().addAll("git-overview-table-value-text", "git-overview-code");

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(InfoRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            if (empty || row == null) {
                setGraphic(null);
                return;
            }

            unbindAll();
            container.getChildren().clear();

            switch (row.type()) {
                case REPOSITORY -> {
                    repoNameText.textProperty().bind(row.primaryProperty());
                    repoStatusText.textProperty().bind(row.secondaryProperty());
                    updateStatusTag(repoStatusTag, row.secondaryProperty().get());
                    container.getChildren().addAll(repoNameText, repoStatusTag);
                }
                case HEAD -> {
                    headBranchText.textProperty().bind(row.primaryProperty());
                    headUpstreamText.textProperty().bind(row.secondaryProperty());
                    container.getChildren().addAll(headBranchText, headUpstreamTag);
                }
                case UPSTREAM -> {
                    upstreamBehindText.textProperty().bind(row.primaryProperty());
                    upstreamAheadText.textProperty().bind(row.secondaryProperty());
                    upstreamFetchText.textProperty().bind(row.tertiaryProperty());
                    container.getChildren().addAll(upstreamBehindTag, upstreamAheadTag, upstreamFetchTag);
                }
                case REMOTE -> {
                    remoteNameText.textProperty().bind(row.primaryProperty());
                    remoteUrlText.textProperty().bind(row.secondaryProperty());
                    container.getChildren().addAll(remoteNameText, remoteSeparatorText, remoteUrlText);
                }
            }

            setGraphic(container);
        }

        private void unbindAll() {
            repoNameText.textProperty().unbind();
            repoStatusText.textProperty().unbind();
            headBranchText.textProperty().unbind();
            headUpstreamText.textProperty().unbind();
            upstreamBehindText.textProperty().unbind();
            upstreamAheadText.textProperty().unbind();
            upstreamFetchText.textProperty().unbind();
            remoteNameText.textProperty().unbind();
            remoteUrlText.textProperty().unbind();
        }

        private static HBox createTag(String... styleClasses) {
            var tag = new HBox(4);
            tag.getStyleClass().addAll(styleClasses);
            return tag;
        }

        private static Text createDot(String... extraClasses) {
            var dot = new Text("•");
            dot.getStyleClass().add("git-overview-dot");
            dot.getStyleClass().addAll(extraClasses);
            return dot;
        }

        private static void updateStatusTag(HBox tag, String status) {
            tag.getStyleClass().removeAll("git-overview-tag-warn", "git-overview-tag-good");
            if ("Dirty".equalsIgnoreCase(status)) {
                tag.getStyleClass().add("git-overview-tag-warn");
            } else {
                tag.getStyleClass().add("git-overview-tag-good");
            }
        }
    }

    private static class InfoRowLabelTableCell extends TableCell<InfoRow, String> {
        private final TextFlow labelFlow = new TextFlow();

        private InfoRowLabelTableCell() {
            labelFlow.getStyleClass().add("git-overview-table-label");
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(String labelKey, boolean empty) {
            super.updateItem(labelKey, empty);
            setText(null);
            if (empty || labelKey == null) {
                setGraphic(null);
                return;
            }

            labelFlow.getChildren().setAll(new LocalizedText(labelKey));
            setGraphic(labelFlow);
        }
    }

    private static class ChangeChip extends RRVBox {
        private final Text countText = new Text();

        private ChangeChip(String label) {
            getStyleClass().add("git-overview-change-chip");
            var labelText = new Text(label);
            countText.getStyleClass().add("git-overview-change-number");
            labelText.getStyleClass().add("git-overview-change-label");
            getChildren().addAll(countText, labelText);
            setCount(0);
        }

        private void setCount(long count) {
            countText.setText(Long.toString(count));
        }
    }
}
