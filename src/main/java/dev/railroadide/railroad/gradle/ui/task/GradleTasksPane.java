package dev.railroadide.railroad.gradle.ui.task;

import dev.railroadide.railroad.gradle.model.GradleBuildModel;
import dev.railroadide.railroad.gradle.model.task.GradleTaskModel;
import dev.railroadide.railroad.gradle.service.GradleModelService;
import dev.railroadide.railroad.gradle.ui.GradleTreeBuilder;
import dev.railroadide.railroad.gradle.ui.GradleTreeViewPane;
import dev.railroadide.railroad.project.Project;

import java.util.Collection;
import java.util.List;

public class GradleTasksPane extends GradleTreeViewPane<GradleTaskModel> {
    public GradleTasksPane(Project project) {
        super(project);
    }

    @Override
    protected GradleTreeBuilder<GradleTaskModel> createTreeBuilder() {
        return new GradleTaskTreeBuilder();
    }

    @Override
    protected Collection<GradleTaskModel> getElementsFromModel(GradleModelService modelService, GradleBuildModel model) {
        return List.copyOf(modelService.getAllTasks());
    }
}
