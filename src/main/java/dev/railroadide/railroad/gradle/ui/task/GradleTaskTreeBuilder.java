package dev.railroadide.railroad.gradle.ui.task;

import dev.railroadide.railroad.gradle.model.GradleProjectModel;
import dev.railroadide.railroad.gradle.model.task.GradleTaskModel;
import dev.railroadide.railroad.gradle.ui.*;
import dev.railroadide.railroad.gradle.ui.tree.GradleProjectElement;
import dev.railroadide.railroad.gradle.ui.tree.GradleTaskElement;
import dev.railroadide.railroad.gradle.ui.tree.GradleTaskGroupElement;
import dev.railroadide.railroad.gradle.ui.tree.GradleTreeElement;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.StringUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GradleTaskTreeBuilder implements GradleTreeBuilder<GradleTaskModel> {
    @Override
    public TreeItem<GradleTreeElement> buildTree(Project project, ObservableList<GradleTaskModel> elements) {
        TreeItem<GradleTreeElement> root = new TreeItem<>();

        Map<GradleProjectModel, List<GradleTaskModel>> tasksByProject = elements.stream()
            .collect(Collectors.groupingBy(GradleTaskModel::project));

        Map<String, GradleProjectModel> projectsByPath = tasksByProject.keySet().stream()
            .collect(Collectors.toMap(GradleProjectModel::path, Function.identity()));

        Map<String, TreeItem<GradleTreeElement>> projectNodes = new HashMap<>();

        for (GradleProjectModel gradleProjectModel : tasksByProject.keySet()) {
            ensureProjectNode(project, gradleProjectModel, projectsByPath, projectNodes, root);
        }

        for (Map.Entry<GradleProjectModel, List<GradleTaskModel>> entry : tasksByProject.entrySet()) {
            TreeItem<GradleTreeElement> projectNode = projectNodes.get(entry.getKey().path());
            if (projectNode == null)
                continue;

            addTasksToProjectNode(project, projectNode, entry.getValue());
        }

        sortTree(root);
        return root;
    }

    private TreeItem<GradleTreeElement> ensureProjectNode(
        Project project,
        GradleProjectModel gradleProject,
        Map<String, GradleProjectModel> projectsByPath,
        Map<String, TreeItem<GradleTreeElement>> projectNodes,
        TreeItem<GradleTreeElement> root
    ) {
        return projectNodes.computeIfAbsent(gradleProject.path(), path -> {
            TreeItem<GradleTreeElement> parentNode = root;
            String parentPath = getParentProjectPath(path);
            if (parentPath != null) {
                GradleProjectModel parentProject = projectsByPath.get(parentPath);
                if (parentProject != null) {
                    parentNode = ensureProjectNode(project, parentProject, projectsByPath, projectNodes, root);
                }
            }

            TreeItem<GradleTreeElement> node =
                new TreeItem<>(new GradleProjectElement(project, gradleProject));
            parentNode.getChildren().add(node);
            return node;
        });
    }

    private void addTasksToProjectNode(Project project, TreeItem<GradleTreeElement> projectNode,
                                       List<GradleTaskModel> projectTasks) {
        Map<String, List<GradleTaskModel>> tasksByGroup = projectTasks.stream()
            .collect(Collectors.groupingBy(task -> {
                String group = task.group();
                return group == null ? "<no-group>" : StringUtils.capitalizeFirstLetterOfEachWord(group);
            }, HashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<GradleTaskModel>> groupEntry : tasksByGroup.entrySet()) {
            String groupName = groupEntry.getKey();
            List<GradleTaskModel> groupTasks = groupEntry.getValue();

            TreeItem<GradleTreeElement> groupNode = new TreeItem<>(
                new GradleTaskGroupElement(groupName));
            projectNode.getChildren().add(groupNode);

            for (GradleTaskModel task : groupTasks) {
                TreeItem<GradleTreeElement> taskNode =
                    new TreeItem<>(new GradleTaskElement(project, task));
                groupNode.getChildren().add(taskNode);
            }
        }
    }

    private void sortTree(TreeItem<GradleTreeElement> node) {
        Comparator<TreeItem<GradleTreeElement>> comparator =
            Comparator.<TreeItem<GradleTreeElement>, Integer>comparing(
                item -> typeRank(item.getValue())
            ).thenComparing(
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

    private int typeRank(GradleTreeElement element) {
        if (element instanceof GradleTaskElement)
            return 0;

        if (element instanceof GradleTaskGroupElement)
            return 1;

        if (element instanceof GradleProjectElement)
            return 2;

        return Integer.MAX_VALUE;
    }
}
