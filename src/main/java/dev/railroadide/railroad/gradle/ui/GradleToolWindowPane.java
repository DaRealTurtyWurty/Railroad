package dev.railroadide.railroad.gradle.ui;

import dev.railroadide.core.ui.RRButton;
import dev.railroadide.core.ui.RRHBox;
import dev.railroadide.core.ui.RRVBox;
import dev.railroadide.core.ui.localized.LocalizedTab;
import dev.railroadide.core.ui.localized.LocalizedTooltip;
import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.gradle.GradleSettings;
import dev.railroadide.railroad.gradle.model.GradleBuildModel;
import dev.railroadide.railroad.gradle.model.GradleModelListener;
import dev.railroadide.railroad.gradle.project.GradleManager;
import dev.railroadide.railroad.project.Project;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.StackedFontIcon;

@Getter
public class GradleToolWindowPane extends RRVBox {
    private final TabPane tabPane;
    private final Tab tasksTab;
    private final Tab dependenciesTab;

    public GradleToolWindowPane(Project project) {
        super();
        getStyleClass().add("gradle-toolwindow-pane");

        GradleManager gradleManager = project.getGradleManager();
        var modelService = gradleManager.getGradleModelService();

        var syncButton = createButtonBarButton(
            FontAwesomeSolid.SYNC,
            "railroad.gradle.toolwindow.button.sync.tooltip",
            "sync-button"
        );
        syncButton.setOnAction(event ->
            gradleManager.getGradleModelService().refreshModel(true));

        var downloadSourcesButton = createButtonBarButton(
            FontAwesomeSolid.DOWNLOAD,
            "railroad.gradle.toolwindow.button.downloadsources.tooltip",
            "download-sources-button"
        );
        downloadSourcesButton.setOnAction(event -> {
            Railroad.LOGGER.info("Downloading Gradle sources...");
            downloadSourcesButton.setDisable(true);
            gradleManager.downloadAllSources().whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    Railroad.LOGGER.error("Failed to download Gradle sources", throwable);
                } else {
                    Railroad.LOGGER.info("Gradle sources downloaded successfully");
                }

                Platform.runLater(() -> downloadSourcesButton.setDisable(false));
            });
        });

        var offlineIcon = new StackedFontIcon();
        offlineIcon.setIconCodes(FontAwesomeSolid.WIFI, FontAwesomeSolid.SLASH);
        var toggleOfflineButton = createButtonBarButton(
            offlineIcon,
            "railroad.gradle.toolwindow.button.toggleoffline.tooltip",
            "toggle-offline-button",
            true
        );
        toggleOfflineButton.setOnAction(event -> {
            GradleSettings gradleSettings = gradleManager.getGradleSettings();
            boolean newOfflineMode = !gradleSettings.isOfflineMode();
            gradleSettings.setOfflineMode(newOfflineMode);
            gradleManager.saveSettings();
            ((RRButton.Toggle) toggleOfflineButton).setSelected(newOfflineMode);
        });

        var listener = new GradleModelListener() {
            private void setButtonsDisabled(boolean disabled) {
                Platform.runLater(() -> {
                    syncButton.setDisable(disabled);
                    downloadSourcesButton.setDisable(disabled);
                    toggleOfflineButton.setDisable(disabled);
                });
            }

            @Override
            public void modelReloadStarted() {
                setButtonsDisabled(true);
            }

            @Override
            public void modelReloadSucceeded(GradleBuildModel model) {
                setButtonsDisabled(false);
            }

            @Override
            public void modelReloadFailed(Throwable error) {
                setButtonsDisabled(false);
            }
        };
        modelService.addListener(listener);

        var buttonBar = new RRHBox(2, syncButton, downloadSourcesButton, toggleOfflineButton);
        buttonBar.getStyleClass().add("gradle-toolwindow-buttonbar");

        getChildren().add(buttonBar);

        this.tasksTab = new LocalizedTab("railroad.gradle.toolwindow.tasks", new GradleTasksPane(project));
        this.dependenciesTab = new LocalizedTab("railroad.gradle.toolwindow.dependencies"/*, new GradleDependenciesPane(project)*/);

        this.tabPane = new TabPane(tasksTab, dependenciesTab);
        tabPane.getStyleClass().add("gradle-toolwindow-tabpane");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        getChildren().add(tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);
    }

    private static RRButton createButtonBarButton(Ikon ikon, String tooltipKey, String styleClass, boolean toggle) {
        var button = toggle ? new RRButton.Toggle("", ikon) : new RRButton("", ikon);
        button.setSquare(true);
        button.setButtonSize(RRButton.ButtonSize.SMALL);
        button.setVariant(RRButton.ButtonVariant.GHOST);
        button.setTooltip(new LocalizedTooltip(tooltipKey));
        button.getStyleClass().addAll("gradle-toolwindow-buttonbar-button", styleClass);
        return button;
    }

    private static RRButton createButtonBarButton(Node graphic, String tooltipKey, String styleClass, boolean toggle) {
        var button = toggle ? new RRButton.Toggle("", graphic) : new RRButton("", graphic);
        button.setSquare(true);
        button.setButtonSize(RRButton.ButtonSize.SMALL);
        button.setVariant(RRButton.ButtonVariant.GHOST);
        button.setTooltip(new LocalizedTooltip(tooltipKey));
        button.getStyleClass().addAll("gradle-toolwindow-buttonbar-button", styleClass);
        return button;
    }

    private static RRButton createButtonBarButton(Ikon ikon, String tooltipKey, String styleClass) {
        return createButtonBarButton(ikon, tooltipKey, styleClass, false);
    }

    public boolean isTasksTabSelected() {
        return this.tabPane.getSelectionModel().getSelectedItem() == tasksTab;
    }

    public boolean isDependenciesTabSelected() {
        return this.tabPane.getSelectionModel().getSelectedItem() == dependenciesTab;
    }
}
