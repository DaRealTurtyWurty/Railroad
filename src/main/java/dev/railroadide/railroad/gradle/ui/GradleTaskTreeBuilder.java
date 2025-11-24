package dev.railroadide.railroad.gradle.ui;

import dev.railroadide.railroad.gradle.model.GradleProjectModel;
import dev.railroadide.railroad.gradle.model.task.GradleTaskModel;
import dev.railroadide.railroad.project.Project;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GradleTaskTreeBuilder {
    public TreeItem<GradleTasksPane.GradleTaskTreeElement> buildTaskTree(Project project, ObservableList<GradleTaskModel> tasks) {
        TreeItem<GradleTasksPane.GradleTaskTreeElement> root = new TreeItem<>();

        Map<GradleProjectModel, List<GradleTaskModel>> tasksByProject = tasks.stream()
            .collect(Collectors.groupingBy(GradleTaskModel::project));

        Map<String, GradleProjectModel> projectsByPath = tasksByProject.keySet().stream()
            .collect(Collectors.toMap(GradleProjectModel::path, Function.identity()));

        Map<String, TreeItem<GradleTasksPane.GradleTaskTreeElement>> projectNodes = new HashMap<>();

        for (GradleProjectModel gradleProjectModel : tasksByProject.keySet()) {
            ensureProjectNode(project, gradleProjectModel, projectsByPath, projectNodes, root);
        }

        for (Map.Entry<GradleProjectModel, List<GradleTaskModel>> entry : tasksByProject.entrySet()) {
            TreeItem<GradleTasksPane.GradleTaskTreeElement> projectNode = projectNodes.get(entry.getKey().path());
            if (projectNode == null)
                continue;

            addTasksToProjectNode(project, projectNode, entry.getValue());
        }

        sortTree(root);
        return root;
    }

    private TreeItem<GradleTasksPane.GradleTaskTreeElement> ensureProjectNode(
        Project project,
        GradleProjectModel gradleProject,
        Map<String, GradleProjectModel> projectsByPath,
        Map<String, TreeItem<GradleTasksPane.GradleTaskTreeElement>> projectNodes,
        TreeItem<GradleTasksPane.GradleTaskTreeElement> root
    ) {
        return projectNodes.computeIfAbsent(gradleProject.path(), path -> {
            TreeItem<GradleTasksPane.GradleTaskTreeElement> parentNode = root;
            String parentPath = getParentProjectPath(path);
            if (parentPath != null) {
                GradleProjectModel parentProject = projectsByPath.get(parentPath);
                if (parentProject != null) {
                    parentNode = ensureProjectNode(project, parentProject, projectsByPath, projectNodes, root);
                }
            }

            TreeItem<GradleTasksPane.GradleTaskTreeElement> node =
                new TreeItem<>(new GradleTasksPane.GradleProjectElement(project, gradleProject));
            parentNode.getChildren().add(node);
            return node;
        });
    }

    private void addTasksToProjectNode(Project project, TreeItem<GradleTasksPane.GradleTaskTreeElement> projectNode,
                                       List<GradleTaskModel> projectTasks) {
        Map<String, List<GradleTaskModel>> tasksByGroup = projectTasks.stream()
            .collect(Collectors.groupingBy(task -> {
                String group = task.group();
                return group == null ? "<no-group>" : capitalizeFirstLetterOfEachWord(group);
            }, HashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<GradleTaskModel>> groupEntry : tasksByGroup.entrySet()) {
            String groupName = groupEntry.getKey();
            List<GradleTaskModel> groupTasks = groupEntry.getValue();

            TreeItem<GradleTasksPane.GradleTaskTreeElement> groupNode = new TreeItem<>(
                new GradleTasksPane.GradleTaskGroupElement(groupName));
            projectNode.getChildren().add(groupNode);

            for (GradleTaskModel task : groupTasks) {
                TreeItem<GradleTasksPane.GradleTaskTreeElement> taskNode =
                    new TreeItem<>(new GradleTasksPane.GradleTaskElement(project, task));
                groupNode.getChildren().add(taskNode);
            }
        }
    }

    private void sortTree(TreeItem<GradleTasksPane.GradleTaskTreeElement> node) {
        Comparator<TreeItem<GradleTasksPane.GradleTaskTreeElement>> comparator =
            Comparator.<TreeItem<GradleTasksPane.GradleTaskTreeElement>, Integer>comparing(
                item -> typeRank(item.getValue())
            ).thenComparing(
                item -> {
                    GradleTasksPane.GradleTaskTreeElement element = item.getValue();
                    return element == null ? "" : element.getName();
                },
                String.CASE_INSENSITIVE_ORDER
            );

        FXCollections.sort(node.getChildren(), comparator);
        for (TreeItem<GradleTasksPane.GradleTaskTreeElement> child : node.getChildren()) {
            sortTree(child);
        }
    }

    private int typeRank(GradleTasksPane.GradleTaskTreeElement element) {
        if (element instanceof GradleTasksPane.GradleTaskElement)
            return 0;

        if (element instanceof GradleTasksPane.GradleTaskGroupElement)
            return 1;

        if (element instanceof GradleTasksPane.GradleProjectElement)
            return 2;

        return Integer.MAX_VALUE;
    }

    private static String capitalizeFirstLetterOfEachWord(String input) {
        String[] words = input.split(" ");
        var capitalized = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                capitalized.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase())
                    .append(" ");
            }
        }

        return capitalized.toString().trim();
    }

    private String getParentProjectPath(String projectPath) {
        if (projectPath == null || ":".equals(projectPath))
            return null;

        String trimmed = projectPath.startsWith(":") ? projectPath.substring(1) : projectPath;
        if (trimmed.isEmpty())
            return null;

        int lastSeparator = trimmed.lastIndexOf(':');
        if (lastSeparator < 0)
            return ":";

        String parentSegments = trimmed.substring(0, lastSeparator);
        if (parentSegments.isEmpty())
            return ":";

        return ":" + parentSegments;
    }
}
