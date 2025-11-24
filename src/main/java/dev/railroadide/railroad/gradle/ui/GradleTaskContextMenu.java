package dev.railroadide.railroad.gradle.ui;

import dev.railroadide.core.ui.localized.LocalizedMenuItem;
import dev.railroadide.railroad.gradle.model.task.GradleTaskModel;
import dev.railroadide.railroad.ide.runconfig.RunConfiguration;
import dev.railroadide.railroad.ide.runconfig.RunConfigurationManager;
import dev.railroadide.railroad.ide.runconfig.RunConfigurationTypes;
import dev.railroadide.railroad.ide.runconfig.defaults.data.GradleRunConfigurationData;
import dev.railroadide.railroad.java.JDKManager;
import dev.railroadide.railroad.project.Project;
import javafx.scene.control.ContextMenu;
import org.jetbrains.annotations.NotNull;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Optional;

public class GradleTaskContextMenu extends ContextMenu {
    public GradleTaskContextMenu(Project project, GradleTaskModel task) {
        super();

        var runIcon = new FontIcon(FontAwesomeSolid.PLAY);
        runIcon.getStyleClass().add("run-button");

        var runItem = new LocalizedMenuItem("railroad.runconfig.run.tooltip", runIcon);
        runItem.setOnAction(event -> {
            var runConfiguration = getOrCreateRunConfig(project, task);
            runConfiguration.run(project);
        });

        var debugIcon = new FontIcon(FontAwesomeSolid.BUG);
        debugIcon.getStyleClass().add("debug-button");

        var debugItem = new LocalizedMenuItem("railroad.runconfig.debug.tooltip", debugIcon);
        debugItem.setOnAction(event -> {
            var runConfiguration = getOrCreateRunConfig(project, task);

            runConfiguration.debug(project);
        });

        getItems().addAll(runItem, debugItem);
    }

    static @NotNull RunConfiguration<GradleRunConfigurationData> getOrCreateRunConfig(Project project, GradleTaskModel task) {
        RunConfigurationManager runConfigManager = project.getRunConfigManager();
        @SuppressWarnings("unchecked")
        Optional<RunConfiguration<GradleRunConfigurationData>> existingRunConfig = runConfigManager.getConfigurations().stream()
            .filter(configuration -> hasExistingRunConfig(task, configuration))
            .map(configuration -> (RunConfiguration<GradleRunConfigurationData>) configuration)
            .findFirst();

        return existingRunConfig.orElseGet(() -> createRunConfig(task, runConfigManager));
    }

    static @NotNull RunConfiguration<GradleRunConfigurationData> createRunConfig(GradleTaskModel task, RunConfigurationManager runConfigManager) {
        var configurationData = new GradleRunConfigurationData();
        configurationData.setGradleProjectPath(task.project().projectDir());
        configurationData.setTask(task.name());
        configurationData.setJavaHome(JDKManager.getDefaultJDK());
        configurationData.setName(task.project().name() + " [" + task.name() + "]");

        var runConfiguration = new RunConfiguration<>(RunConfigurationTypes.GRADLE, configurationData);
        runConfigManager.addConfiguration(runConfiguration);
        runConfigManager.setSelectedConfiguration(runConfiguration);
        return runConfiguration;
    }

    static boolean hasExistingRunConfig(GradleTaskModel task, RunConfiguration<?> configuration) {
        if (configuration.type() != RunConfigurationTypes.GRADLE) return false;

        var data = (GradleRunConfigurationData) configuration.data();
        return data.getGradleProjectPath().equals(task.project().projectDir()) && data.getTask().equals(task.name());
    }
}
