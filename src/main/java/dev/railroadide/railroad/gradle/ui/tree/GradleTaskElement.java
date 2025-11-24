package dev.railroadide.railroad.gradle.ui.tree;

import dev.railroadide.railroad.gradle.model.task.GradleTaskModel;
import dev.railroadide.railroad.gradle.ui.task.GradleTaskContextMenu;
import dev.railroadide.railroad.project.Project;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.devicons.Devicons;

@Getter
public class GradleTaskElement extends GradleTreeElement {
    private final Project project;
    private final GradleTaskModel task;

    public GradleTaskElement(Project project, GradleTaskModel task) {
        super(task.name());

        this.project = project;
        this.task = task;
    }

    @Override
    public Ikon getIcon() {
        return Devicons.TERMINAL;
    }

    @Override
    public String getStyleClass() {
        return "gradle-task-element";
    }

    @Override
    public Tooltip getTooltip() {
        return new Tooltip(this.task.description());
    }

    @Override
    public ContextMenu getContextMenu() {
        return new GradleTaskContextMenu(this.project, this.task);
    }
}
