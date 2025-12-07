package dev.railroadide.railroad.gradle.ui.task;

import dev.railroadide.railroad.gradle.model.GradleBuildModel;
import dev.railroadide.railroad.gradle.service.GradleModelService;
import dev.railroadide.railroad.gradle.ui.GradleTreeBuilder;
import dev.railroadide.railroad.gradle.ui.GradleTreeViewPane;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroadplugin.dto.RailroadGradleTask;
import dev.railroadide.railroadplugin.dto.RailroadProject;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

public class GradleTasksPane extends GradleTreeViewPane<RailroadGradleTask> {
    public GradleTasksPane(Project project) {
        super(project);
    }

    @Override
    protected GradleTreeBuilder<RailroadGradleTask> createTreeBuilder() {
        return new GradleTaskTreeBuilder();
    }

    @Override
    protected Collection<RailroadGradleTask> getElementsFromModel(GradleModelService modelService, GradleBuildModel model) {
        return List.copyOf(modelService.getCachedModel()
            .map(GradleBuildModel::project)
            .map(RailroadProject::getModules)
            .map(Collection::stream)
            .orElseGet(Stream::empty)
            .flatMap(module -> module.getTasks().stream())
            .toList());
    }
}
