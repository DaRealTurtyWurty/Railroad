package dev.railroadide.railroad.gradle.ui.tree;

import dev.railroadide.railroad.gradle.ui.GradleProjectContextMenu;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.icon.RailroadBrandsIcon;
import dev.railroadide.railroadplugin.dto.RailroadModule;
import javafx.scene.control.ContextMenu;
import org.kordamp.ikonli.Ikon;

public class GradleProjectElement extends GradleTreeElement {
    private final Project project;
    private final RailroadModule module;

    public GradleProjectElement(Project project, RailroadModule module) {
        super(module.getName() == null ? "Unnamed Project" : module.getName());

        this.project = project;
        this.module = module;
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
        return new GradleProjectContextMenu(this.project, this.module);
    }
}
