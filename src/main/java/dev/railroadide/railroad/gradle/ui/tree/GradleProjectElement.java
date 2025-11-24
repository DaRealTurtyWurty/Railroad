package dev.railroadide.railroad.gradle.ui.tree;

import dev.railroadide.railroad.gradle.model.GradleProjectModel;
import dev.railroadide.railroad.gradle.ui.GradleProjectContextMenu;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.icon.RailroadBrandsIcon;
import javafx.scene.control.ContextMenu;
import org.kordamp.ikonli.Ikon;

public class GradleProjectElement extends GradleTreeElement {
    private final Project project;
    private final GradleProjectModel gradleProject;

    public GradleProjectElement(Project project, GradleProjectModel gradleProject) {
        super(gradleProject.name() == null ? "Unnamed Project" : gradleProject.name());

        this.project = project;
        this.gradleProject = gradleProject;
    }

    @Override
    public Ikon getIcon() {
        return RailroadBrandsIcon.GRADLE;
    }

    @Override
    public String getStyleClass() {
        return "gradle-project-element";
    }

    @Override
    public ContextMenu getContextMenu() {
        return new GradleProjectContextMenu(this.project, this.gradleProject);
    }
}
