package dev.railroadide.railroad.ide.ui.git.commit.details;

import dev.railroadide.core.ui.*;
import dev.railroadide.core.ui.localized.LocalizedText;
import dev.railroadide.core.ui.styling.ButtonVariant;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.TimeFormatter;
import dev.railroadide.railroad.vcs.git.commit.GitCommit;
import dev.railroadide.railroad.vcs.git.diff.GitAdditionsDeletions;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GitCommitDetailsPane extends RRVBox {
    public static final String DEFAULT_TITLE = "Commit Details";
    private final StringProperty title = new SimpleStringProperty(DEFAULT_TITLE);
    private final ObjectProperty<GitCommit> commit = new SimpleObjectProperty<>();

    private final Project project;

    private String headCommitHash = "";
    private Map<String, List<String>> tagsByCommit = Map.of();

    public GitCommitDetailsPane(Project project) {
        super();
        this.project = project;
        getStyleClass().add("git-commit-details-root");

        setAlignment(Pos.CENTER);
        var emptyState = new LocalizedText("railroad.git.commit.details.no_commit_selected");
        emptyState.getStyleClass().add("git-commit-details-empty-state");
        getChildren().add(emptyState);

        commit.addListener((obs, oldCommit, newCommit) -> updateCommitDetails(newCommit));

        project.getGitManager().getCommitListMetadata().thenAccept(metadata -> Platform.runLater(() -> {
            headCommitHash = metadata.headCommitHash();
            tagsByCommit = metadata.tagsByCommit();
        }));
    }

    private void updateCommitDetails(GitCommit newCommit) {
        getChildren().clear();
        if (newCommit == null) {
            var emptyState = new LocalizedText("railroad.git.commit.details.no_commit_selected");
            emptyState.getStyleClass().add("git-commit-details-empty-state");
            getChildren().add(emptyState);
            setAlignment(Pos.CENTER);
            title.set(DEFAULT_TITLE);
        } else {
            var detailsView = new GitCommitDetailsView(project, newCommit);
            getChildren().add(detailsView);
            setAlignment(Pos.TOP_CENTER);
            title.set("Commit: " + newCommit.shortHash());
        }
    }

    public StringProperty titleProperty() {
        return title;
    }

    public void setCommit(GitCommit commit) {
        this.commit.set(this.project.getGitManager().getCommitWithBody(commit));
    }

    private class GitCommitDetailsView extends RRVBox {
        public GitCommitDetailsView(Project project, GitCommit commit) {
            super();
            getStyleClass().add("git-commit-details-view");
            setSpacing(14);
            setAlignment(Pos.TOP_LEFT);

            var subject = new Text(commit.subject());
            subject.getStyleClass().add("git-commit-details-subject");

            var detailsHBox = new RRHBox(2);
            detailsHBox.getStyleClass().add("git-commit-details-hbox");
            detailsHBox.setAlignment(Pos.CENTER_LEFT);

            var authorText = new LocalizedText("railroad.git.commit.details.author", commit.authorName());
            authorText.getStyleClass().addAll("git-commit-details-author", "git-commit-details-meta-text");
            detailsHBox.getChildren().add(authorText);

            var committedText = new LocalizedText("railroad.git.commit.details.committed", TimeFormatter.formatDateTime(commit.authorTimestampEpochSeconds() * 1000L));
            var committedAnimation = new Timeline(new KeyFrame(
                Duration.seconds(1),
                event -> committedText.setKeyAndArgs("railroad.git.commit.details.committed", TimeFormatter.formatDateTime(commit.authorTimestampEpochSeconds() * 1000L))
            ));
            committedAnimation.setCycleCount(Timeline.INDEFINITE);
            committedAnimation.play();
            committedText.getStyleClass().addAll("git-commit-details-committed-time", "git-commit-details-meta-text");
            detailsHBox.getChildren().add(committedText);

            var hashText = new LocalizedText("railroad.git.commit.details.hash", commit.hash());
            hashText.getStyleClass().addAll("git-commit-details-hash", "git-commit-details-meta-text");
            detailsHBox.getChildren().add(hashText);

            var headerCard = new RRVBox(6);
            headerCard.getStyleClass().add("git-commit-header-card");
            headerCard.getChildren().addAll(subject, detailsHBox);
            getChildren().add(headerCard);

            var buttonsHBox = new RRHBox(5);
            buttonsHBox.getStyleClass().add("git-commit-details-buttons-hbox");
            buttonsHBox.setAlignment(Pos.CENTER_LEFT);

            var copyHashButton = new RRButton("railroad.git.commit.details.button.copy_hash", FontAwesomeSolid.COPY);
            copyHashButton.setVariant(ButtonVariant.PRIMARY);
            copyHashButton.setOnAction(event -> {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                var content = new ClipboardContent();
                content.putString(commit.hash());
                clipboard.setContent(content);
            });
            buttonsHBox.getChildren().add(copyHashButton);

            var checkoutButton = new RRButton("railroad.git.commit.details.button.checkout_commit", FontAwesomeSolid.CHECK);
            checkoutButton.setVariant(ButtonVariant.PRIMARY);
            checkoutButton.setOnAction(event -> {
                // TODO: Confirm with the user about checking out (bring up stash dialog if there are uncommitted changes)
                // project.getGitManager().checkoutCommit(commit.hash())
            });
            buttonsHBox.getChildren().add(checkoutButton);

            var newBranchButton = new RRButton("railroad.git.commit.details.button.create_branch", FontAwesomeSolid.CODE_BRANCH);
            newBranchButton.setVariant(ButtonVariant.PRIMARY);
            newBranchButton.setOnAction(event -> {
                // TODO: Bring up a dialog to enter branch name and then create the branch
            });
            buttonsHBox.getChildren().add(newBranchButton);

            var tagButton = new RRButton("railroad.git.commit.details.button.create_tag", FontAwesomeSolid.TAG);
            tagButton.setVariant(ButtonVariant.PRIMARY);
            tagButton.setOnAction(event -> {
                // TODO: Bring up a dialog to enter tag name and then create the tag
            });
            buttonsHBox.getChildren().add(tagButton);

            var cherryPickButton = new RRButton("railroad.git.commit.details.button.cherry_pick", FontAwesomeSolid.MAGNET);
            cherryPickButton.setVariant(ButtonVariant.PRIMARY);
            cherryPickButton.setOnAction(event -> {
                // TODO: Confirm with the user about cherry-picking the commit
                // project.getGitManager().cherryPickCommit(commit.hash())
            });
            buttonsHBox.getChildren().add(cherryPickButton);

            var revertButton = new RRButton("railroad.git.commit.details.button.revert_commit", FontAwesomeSolid.UNDO);
            revertButton.setVariant(ButtonVariant.DANGER);
            revertButton.setOnAction(event -> {
                // TODO: Confirm with the user about reverting the commit
                // project.getGitManager().revertCommit(commit.hash())
            });
            buttonsHBox.getChildren().add(revertButton);

            getChildren().add(buttonsHBox);

            var tableVbox = new RRVBox(5);
            tableVbox.getStyleClass().addAll("git-commit-details-info-vbox", "git-commit-details-info-card");
            tableVbox.setAlignment(Pos.TOP_LEFT);

            var parentsHbox = new RRHBox(4);
            parentsHbox.getStyleClass().add("git-commit-details-info-row");
            var parentsText = new LocalizedText("railroad.git.commit.details.parents");
            parentsText.getStyleClass().add("git-commit-details-parents-label");
            parentsHbox.getChildren().add(parentsText);

            var parentHashesFlow = new RRFlowPane(4, 4);
            parentHashesFlow.getStyleClass().add("git-commit-details-parent-hashes-flow");
            for (var parentHash : commit.parentHashes()) {
                var parentHashText = new Text(parentHash);
                parentHashText.getStyleClass().add("git-commit-details-parent-hash");
                var parentHashChip = new RRHBox(4);
                parentHashChip.getStyleClass().add("git-commit-details-chip");
                parentHashChip.setAlignment(Pos.CENTER_LEFT);
                parentHashChip.getChildren().add(parentHashText);
                parentHashesFlow.getChildren().add(parentHashChip);
            }
            parentsHbox.getChildren().add(parentHashesFlow);
            tableVbox.getChildren().add(parentsHbox);
            HBox.setHgrow(parentHashesFlow, Priority.ALWAYS);

            var committerHbox = new RRHBox(2);
            committerHbox.getStyleClass().add("git-commit-details-info-row");
            var committerText = new LocalizedText("railroad.git.commit.details.committer");
            committerText.getStyleClass().add("git-commit-details-committer-label");
            committerHbox.getChildren().add(committerText);

            var committerFlow = new RRFlowPane(4, 4);
            committerFlow.getStyleClass().add("git-commit-details-committer-flow");
            var committerNameText = new Text(commit.committerName());
            committerNameText.getStyleClass().add("git-commit-details-committer-name");
            var committerNameChip = new RRHBox(4);
            committerNameChip.getStyleClass().add("git-commit-details-chip");
            committerNameChip.setAlignment(Pos.CENTER_LEFT);
            committerNameChip.getChildren().add(committerNameText);
            committerFlow.getChildren().add(committerNameChip);
            var committerEmailText = new Text("<" + commit.committerEmail() + ">");
            committerEmailText.getStyleClass().add("git-commit-details-committer-email");
            var committerEmailChip = new RRHBox(4);
            committerEmailChip.getStyleClass().add("git-commit-details-chip");
            committerEmailChip.setAlignment(Pos.CENTER_LEFT);
            committerEmailChip.getChildren().add(committerEmailText);
            committerFlow.getChildren().add(committerEmailChip);
            committerHbox.getChildren().add(committerFlow);
            tableVbox.getChildren().add(committerHbox);
            HBox.setHgrow(committerFlow, Priority.ALWAYS);

            var refsHbox = new RRHBox(4);
            refsHbox.getStyleClass().add("git-commit-details-info-row");
            var refsText = new LocalizedText("railroad.git.commit.details.refs");
            refsText.getStyleClass().add("git-commit-details-refs-label");
            refsHbox.getChildren().add(refsText);

            var refsFlow = new RRFlowPane(4, 4);
            refsFlow.getStyleClass().add("git-commit-details-refs-flow");
            List<String> tags = new ArrayList<>(tagsByCommit.getOrDefault(commit.hash(), List.of()));
            if (commit.hash().equals(headCommitHash)) {
                tags.add("HEAD");
            }

            for (String ref : tags) {
                var refText = new Text(ref);
                refText.getStyleClass().add("git-commit-details-ref");
                var refChip = new RRHBox(4);
                refChip.getStyleClass().add("git-commit-details-chip");
                refChip.setAlignment(Pos.CENTER_LEFT);
                refChip.getChildren().add(refText);
                refsFlow.getChildren().add(refChip);
            }
            refsHbox.getChildren().add(refsFlow);
            tableVbox.getChildren().add(refsHbox);
            HBox.setHgrow(refsFlow, Priority.ALWAYS);

            getChildren().add(tableVbox);

            var messageVbox = new RRVBox();
            messageVbox.getStyleClass().addAll("git-commit-details-message-vbox", "git-commit-details-message-card");

            var messageHeadingHbox = new RRHBox();
            messageHeadingHbox.getStyleClass().add("git-commit-details-message-heading-hbox");

            var messageHeading = new LocalizedText("railroad.git.commit.details.message");
            messageHeading.getStyleClass().add("git-commit-details-message-heading");
            messageHeadingHbox.getChildren().add(messageHeading);

            var spacer = new Region();
            messageHeadingHbox.getChildren().add(spacer);
            RRHBox.setHgrow(spacer, Priority.ALWAYS);

            List<GitAdditionsDeletions> additionsDeletions = project.getGitManager().getAdditionsDeletions(commit.hash());
            int additions = additionsDeletions.stream().mapToInt(GitAdditionsDeletions::additions).sum();
            int deletions = additionsDeletions.stream().mapToInt(GitAdditionsDeletions::deletions).sum();
            var additionsDeletionsText = new Text(
                "+" + additions + " " +
                    "−" + deletions
            );
            additionsDeletionsText.getStyleClass().add("git-commit-details-additions-deletions");

            var dotText = new Text("•");
            dotText.getStyleClass().add("git-commit-details-message-dot");

            var filesChangedText = new LocalizedText("railroad.git.commit.details.files_changed", additionsDeletions.size());
            filesChangedText.getStyleClass().add("git-commit-details-files-changed");
            var statsHBox = new RRHBox(6);
            statsHBox.getStyleClass().add("git-commit-details-stats-hbox");
            statsHBox.setAlignment(Pos.CENTER_LEFT);
            statsHBox.getChildren().addAll(additionsDeletionsText, dotText, filesChangedText);
            messageHeadingHbox.getChildren().add(statsHBox);

            messageVbox.getChildren().add(messageHeadingHbox);
            var messageContent = new RRTextArea("railroad.git.commit.details.message_content.placeholder");
            messageContent.getStyleClass().add("git-commit-details-message-content");
            messageContent.setEditable(false);
            messageContent.setWrapText(true);
            messageContent.setText(commit.body());
            messageVbox.getChildren().add(messageContent);
            getChildren().add(messageVbox);
        }
    }
}
