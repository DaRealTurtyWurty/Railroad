package dev.railroadide.railroad.gradle.ui.deps;

import dev.railroadide.locatedependencies.ConfigurationTree;
import dev.railroadide.locatedependencies.DependencyNode;
import dev.railroadide.railroad.gradle.model.GradleBuildModel;
import dev.railroadide.railroad.gradle.service.GradleModelService;
import dev.railroadide.railroad.gradle.ui.GradleTreeBuilder;
import dev.railroadide.railroad.gradle.ui.GradleTreeViewPane;
import dev.railroadide.railroad.project.Project;

import java.util.Collection;
import java.util.List;

public class GradleDependenciesPane extends GradleTreeViewPane<ConfigurationTree> {
    public GradleDependenciesPane(Project project) {
        super(project);
    }

    @Override
    protected GradleTreeBuilder<ConfigurationTree> createTreeBuilder() {
        return new GradleDependencyTreeBuilder();
    }

    @Override
    protected Collection<ConfigurationTree> getElementsFromModel(GradleModelService modelService, GradleBuildModel model) {
        List<ConfigurationTree> configurations = modelService.getAllConfigurations();
        for (ConfigurationTree configuration : configurations) {
            System.out.println("Configuration: " + configuration.configuration());
            for (DependencyNode dependency : configuration.dependencies()) {
                System.out.println("  Dependency: " + dependency.name());
            }
        }

        return List.copyOf(configurations);
    }
}
