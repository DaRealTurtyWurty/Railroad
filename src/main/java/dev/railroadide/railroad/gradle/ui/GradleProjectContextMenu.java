package dev.railroadide.railroad.gradle.ui;

import dev.railroadide.core.ui.RRBorderPane;
import dev.railroadide.core.ui.localized.LocalizedMenuItem;
import dev.railroadide.railroad.gradle.model.GradleProjectModel;
import dev.railroadide.railroad.ide.projectexplorer.PathItem;
import dev.railroadide.railroad.ide.projectexplorer.ProjectExplorerPane;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.FileUtils;
import dev.railroadide.railroad.utility.icon.RailroadBrandsIcon;
import javafx.scene.control.ContextMenu;
import javafx.stage.Window;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class GradleProjectContextMenu extends ContextMenu {
    public GradleProjectContextMenu(Project project, GradleProjectModel gradleProject) {
        super();

        var openGradleConfig = new LocalizedMenuItem("railroad.gradle.tools.ctx_menu.open_gradle_config", new FontIcon(RailroadBrandsIcon.GRADLE));
        openGradleConfig.setOnAction(event -> {
            Path buildFile = findBuildScript(gradleProject);
            if (buildFile == null)
                return;

            // TODO: Eventually we will have a system like Project#getFileManager to handle opening files
            Window owner = getOwnerWindow();
            if (owner != null && owner.getScene() != null && owner.getScene().getRoot() instanceof RRBorderPane borderPane) {
                ProjectExplorerPane.openFile(project, new PathItem(buildFile), borderPane);
            } else {
                // Fallback to system handler if we cannot locate the IDE root pane
                FileUtils.openInDefaultApplication(buildFile);
            }
        });

        var syncItem = new LocalizedMenuItem("railroad.gradle.tools.ctx_menu.sync", new FontIcon(FontAwesomeSolid.SYNC));
        syncItem.setOnAction(event -> project.getGradleManager().getGradleModelService().refreshModel(true));

        getItems().addAll(openGradleConfig, syncItem);
    }

    private Path findBuildScript(GradleProjectModel gradleProject) {
        if (gradleProject == null || gradleProject.projectDir() == null)
            return null;

        List<String> candidates = List.of(
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle"
        );

        for (String candidate : candidates) {
            Path buildFile = gradleProject.projectDir().resolve(candidate);
            if (Files.isRegularFile(buildFile))
                return buildFile;
        }

        return null;
    }
}
