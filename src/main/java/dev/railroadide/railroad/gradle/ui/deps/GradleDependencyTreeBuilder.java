package dev.railroadide.railroad.gradle.ui.deps;

import dev.railroadide.locatedependencies.ConfigurationTree;
import dev.railroadide.locatedependencies.DependencyNode;
import dev.railroadide.railroad.gradle.ui.GradleTreeBuilder;
import dev.railroadide.railroad.gradle.ui.tree.GradleConfigurationElement;
import dev.railroadide.railroad.gradle.ui.tree.GradleDependencyElement;
import dev.railroadide.railroad.gradle.ui.tree.GradleTreeElement;
import dev.railroadide.railroad.project.Project;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.util.Comparator;
import java.util.List;

// TODO: Do not display duplicate dependencies in the tree
// TODO: Don't show empty configurations
// TODO: Include the projects in the tree
public class GradleDependencyTreeBuilder implements GradleTreeBuilder<ConfigurationTree> {
    @Override
    public TreeItem<GradleTreeElement> buildTree(Project project, ObservableList<ConfigurationTree> elements) {
        TreeItem<GradleTreeElement> root = new TreeItem<>();

        for (ConfigurationTree configurationTree : elements) {
            if (configurationTree == null)
                continue;

            TreeItem<GradleTreeElement> configurationNode = new TreeItem<>(
                new GradleConfigurationElement(configurationTree.configuration()));
            root.getChildren().add(configurationNode);

            addDependencies(configurationNode, configurationTree.dependencies());
        }

        sortTree(root);
        return root;
    }

    private void addDependencies(TreeItem<GradleTreeElement> parent, List<DependencyNode> dependencies) {
        if (dependencies == null)
            return;

        for (DependencyNode dependency : dependencies) {
            TreeItem<GradleTreeElement> dependencyNode = new TreeItem<>(new GradleDependencyElement(dependency));
            parent.getChildren().add(dependencyNode);

            addDependencies(dependencyNode, dependency.dependencies());
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
