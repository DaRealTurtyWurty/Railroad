package dev.railroadide.railroad.gradle.ui;

import dev.railroadide.core.ui.localized.LocalizedMenuItem;
import dev.railroadide.railroad.gradle.model.task.GradleTaskModel;
import dev.railroadide.railroad.ide.runconfig.RunConfiguration;
import dev.railroadide.railroad.ide.runconfig.RunConfigurationTypes;
import dev.railroadide.railroad.ide.runconfig.defaults.data.GradleRunConfigurationData;
import dev.railroadide.railroad.java.JDKManager;
import dev.railroadide.railroad.project.Project;
import javafx.scene.control.ContextMenu;
import org.jetbrains.annotations.NotNull;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

public class GradleTaskContextMenu extends ContextMenu {
    public GradleTaskContextMenu(Project project, GradleTaskModel task) {
        super();

        var runIcon = new FontIcon(FontAwesomeSolid.PLAY);
        runIcon.getStyleClass().add("run-button");

        var runItem = new LocalizedMenuItem("railroad.runconfig.run.tooltip", runIcon);
        runItem.setOnAction(event -> {
            var runConfiguration = createRunConfig(project, task);
            runConfiguration.run(project);
        });

        var debugIcon = new FontIcon(FontAwesomeSolid.BUG);
        debugIcon.getStyleClass().add("debug-button");

        var debugItem = new LocalizedMenuItem("railroad.runconfig.debug.tooltip", debugIcon);
        debugItem.setOnAction(event -> {
            var runConfiguration = createRunConfig(project, task);

            runConfiguration.debug(project);
        });

        getItems().addAll(runItem, debugItem);
    }

    static @NotNull RunConfiguration<GradleRunConfigurationData> createRunConfig(Project project, GradleTaskModel task) {
        var configurationData = new GradleRunConfigurationData();
        configurationData.setGradleProjectPath(task.project().projectDir());
        configurationData.setTask(task.name());
        configurationData.setJavaHome(JDKManager.getDefaultJDK());
        configurationData.setName(task.project().name() + " [" + task.name() + "]");
        var runConfiguration = new RunConfiguration<>(RunConfigurationTypes.GRADLE, configurationData);
        project.getRunConfigManager().addConfiguration(runConfiguration);
        project.getRunConfigManager().getSelectedConfiguration().setValue(runConfiguration);
        return runConfiguration;
    }
}
