package dev.railroadide.railroad.gradle.ui.deps;

import dev.railroadide.railroad.gradle.ui.GradleTreeBuilder;
import dev.railroadide.railroad.gradle.ui.tree.GradleConfigurationElement;
import dev.railroadide.railroad.gradle.ui.tree.GradleDependencyElement;
import dev.railroadide.railroad.gradle.ui.tree.GradleTreeElement;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroadplugin.dto.RailroadConfiguration;
import dev.railroadide.railroadplugin.dto.RailroadDependency;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import org.gradle.tooling.model.DomainObjectSet;

import java.util.Collection;
import java.util.Comparator;

// TODO: Include the projects in the tree
public class GradleDependencyTreeBuilder implements GradleTreeBuilder<RailroadConfiguration> {
    @Override
    public TreeItem<GradleTreeElement> buildTree(Project project, ObservableList<RailroadConfiguration> elements) {
        TreeItem<GradleTreeElement> root = new TreeItem<>();

        for (RailroadConfiguration configurationTree : elements) {
            if (configurationTree == null)
                continue;

            DomainObjectSet<? extends RailroadDependency> dependencies = configurationTree.getDependencies();
            if (dependencies == null || dependencies.isEmpty())
                continue;

            TreeItem<GradleTreeElement> configurationNode = new TreeItem<>(
                new GradleConfigurationElement(configurationTree.getName()));
            root.getChildren().add(configurationNode);

            addDependencies(configurationNode, dependencies);
        }

        sortTree(root);
        return root;
    }

    private void addDependencies(TreeItem<GradleTreeElement> parent, Collection<? extends RailroadDependency> dependencies) {
        if (dependencies == null)
            return;

        for (RailroadDependency dependency : dependencies) {
            TreeItem<GradleTreeElement> dependencyNode = new TreeItem<>(new GradleDependencyElement(dependency));
            parent.getChildren().add(dependencyNode);

            addDependencies(dependencyNode, dependency.getChildren());
        }
    }

    private void sortTree(TreeItem<GradleTreeElement> node) {
        Comparator<TreeItem<GradleTreeElement>> comparator = Comparator.comparing(
            item -> {
                GradleTreeElement element = item.getValue();
                return element == null ? "" : element.getName();
            },
            String.CASE_INSENSITIVE_ORDER
        );

        FXCollections.sort(node.getChildren(), comparator);
        for (TreeItem<GradleTreeElement> child : node.getChildren()) {
            sortTree(child);
        }
    }
}
