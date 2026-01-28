package dev.railroadide.railroad.ide.ui.git.overview;

import dev.railroadide.core.ui.RRTableView;
import dev.railroadide.core.ui.RRVBox;
import dev.railroadide.core.ui.localized.LocalizedText;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.vcs.git.GitIdentity;
import dev.railroadide.railroad.vcs.git.GitManager;
import dev.railroadide.railroad.vcs.git.SigningStatus;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.beans.binding.Bindings;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

public class GitOverviewIdentityPane extends RRVBox {
    private final RRTableView<InfoRow> identityTable = new RRTableView<>();
    private final InfoRow userNameRow = new InfoRow("railroad.git.overview.identity.user.label");
    private final InfoRow userEmailRow = new InfoRow("railroad.git.overview.identity.email.label");
    private final InfoRow signedRow = new InfoRow("railroad.git.overview.identity.signing.label");
    private final InfoRow gitVersionRow = new InfoRow("railroad.git.overview.identity.git_version.label");

    public GitOverviewIdentityPane(Project project) {
        getStyleClass().add("git-overview-identity-pane");

        configureTable();
        VBox.setVgrow(identityTable, Priority.ALWAYS);
        getChildren().add(identityTable);

        GitManager gitManager = project.getGitManager();
        updateIdentityInfo(gitManager);
        listenForUpdates(gitManager);
    }

    private void configureTable() {
        identityTable.getStyleClass().addAll("git-overview-identity-table", "no-header");
        identityTable.setEditable(false);
        identityTable.setFocusTraversable(false);
        identityTable.setSelectionModel(null);
        identityTable.setPrefWidth(Double.MAX_VALUE);
        identityTable.setMaxWidth(Double.MAX_VALUE);
        identityTable.setFixedCellSize(28);
        identityTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        var labelColumn = new TableColumn<InfoRow, String>();
        labelColumn.setSortable(false);
        labelColumn.setReorderable(false);
        labelColumn.setResizable(false);
        labelColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().labelKey()));
        labelColumn.setCellFactory(column -> new InfoRowLabelTableCell());
        labelColumn.setMinWidth(150);

        var valueColumn = new TableColumn<InfoRow, String>();
        valueColumn.setSortable(false);
        valueColumn.setReorderable(false);
        valueColumn.setResizable(true);
        valueColumn.setCellValueFactory(data -> data.getValue().valueProperty());
        valueColumn.setCellFactory(column -> new InfoRowValueTableCell());
        valueColumn.prefWidthProperty().bind(identityTable.widthProperty().subtract(labelColumn.widthProperty()).subtract(8));

        // noinspection unchecked
        identityTable.getColumns().addAll(labelColumn, valueColumn);

        ObservableList<InfoRow> rows = FXCollections.observableArrayList(
            userNameRow,
            userEmailRow,
            signedRow,
            gitVersionRow
        );
        identityTable.setItems(rows);
        identityTable.prefHeightProperty().bind(Bindings.size(identityTable.getItems())
            .multiply(identityTable.fixedCellSizeProperty()).add(2));
        identityTable.maxHeightProperty().bind(identityTable.prefHeightProperty());
    }

    private void updateIdentityInfo(GitManager gitManager) {
        GitIdentity identity = gitManager.getIdentity();
        Platform.runLater(() -> {
            if (identity == null) {
                userNameRow.setValue("Not Set");
                userEmailRow.setValue("Not Set");
                signedRow.setValue("Not Configured");
                gitVersionRow.setValue("Unknown");
            } else {
                String userName = identity.userName();
                String userEmail = identity.email();
                SigningStatus signingStatus = identity.signing();
                String gitVersion = identity.gitVersion();

                userNameRow.setValue(userName != null ? userName : "Not Set");
                userEmailRow.setValue(userEmail != null ? userEmail : "Not Set");
                signedRow.setValue(signingStatus != null ? signingStatus.toString() : "Not Configured");
                gitVersionRow.setValue(gitVersion != null ? gitVersion : "Unknown");
            }
        });
    }

    private void listenForUpdates(GitManager gitManager) {
        gitManager.gitIdentityProperty().addListener((obs, oldIdentity, newIdentity) ->
            updateIdentityInfo(gitManager));
    }

    private static final class InfoRow {
        private final String labelKey;
        private final StringProperty value = new SimpleStringProperty("");

        private InfoRow(String labelKey) {
            this.labelKey = labelKey;
        }

        private String labelKey() {
            return labelKey;
        }

        private StringProperty valueProperty() {
            return value;
        }

        private void setValue(String value) {
            this.value.set(value);
        }
    }

    private static class InfoRowLabelTableCell extends TableCell<InfoRow, String> {
        private final TextFlow labelFlow = new TextFlow();

        private InfoRowLabelTableCell() {
            labelFlow.getStyleClass().add("git-overview-identity-label");
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

    private static class InfoRowValueTableCell extends TableCell<InfoRow, String> {
        private final Text valueText = new Text();
        private final TextFlow valueFlow = new TextFlow(valueText);

        private InfoRowValueTableCell() {
            valueText.getStyleClass().add("git-overview-identity-value-text");
            valueFlow.getStyleClass().add("git-overview-identity-value");
            valueFlow.prefWidthProperty().bind(widthProperty());
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            setText(null);
            if (empty) {
                setGraphic(null);
                return;
            }

            valueText.setText(value == null ? "" : value);
            setGraphic(valueFlow);
        }
    }
}
