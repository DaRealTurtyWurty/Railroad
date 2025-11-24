package dev.railroadide.railroad.gradle.ui;

import dev.railroadide.core.ui.RRVBox;
import dev.railroadide.railroad.gradle.model.GradleBuildModel;
import dev.railroadide.railroad.gradle.model.GradleModelListener;
import dev.railroadide.railroad.gradle.model.GradleProjectModel;
import dev.railroadide.railroad.gradle.model.task.GradleTaskModel;
import dev.railroadide.railroad.gradle.service.GradleModelService;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.icon.RailroadBrandsIcon;
import io.github.palexdev.materialfx.controls.MFXProgressSpinner;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.devicons.Devicons;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.concurrent.atomic.AtomicBoolean;

public class GradleTasksPane extends RRVBox {
    private final ObservableList<GradleTaskModel> tasks = FXCollections.observableArrayList();

    private final TreeView<GradleTasksPane.GradleTaskTreeElement> taskTreeView = new TreeView<>();
    private final MFXProgressSpinner loadingSpinner = new MFXProgressSpinner();
    private final StackPane loadingContainer = new StackPane(loadingSpinner);
    private final AtomicBoolean isLoading = new AtomicBoolean(true);

    public GradleTasksPane(Project project) {
        super();
        getStyleClass().add("gradle-tasks-pane");

        loadingSpinner.getStyleClass().add("gradle-tasks-loading-spinner");
        loadingContainer.setAlignment(Pos.CENTER);
        loadingContainer.prefHeightProperty().bind(heightProperty());
        loadingContainer.prefWidthProperty().bind(widthProperty());

        taskTreeView.getStyleClass().add("gradle-tasks-tree-view");
        taskTreeView.setShowRoot(false);
        taskTreeView.setCellFactory(param -> new GradleTaskTreeCell());
        taskTreeView.prefHeightProperty().bind(heightProperty());
        tasks.addListener((ListChangeListener<? super GradleTaskModel>) change -> {
            Platform.runLater(() -> {
                var treeBuilder = new GradleTaskTreeBuilder();
                taskTreeView.setRoot(treeBuilder.buildTaskTree(project, tasks));
            });
        });

        updateLoadingState();

        GradleModelService modelService = project.getGradleManager().getGradleModelService();
        modelService.refreshModel(true);
        modelService.addListener(new GradleModelListener() {
            @Override
            public void modelReloadStarted() {
                isLoading.set(true);
                updateLoadingState();
            }

            @Override
            public void modelReloadSucceeded(GradleBuildModel model) {
                isLoading.set(false);
                tasks.setAll(modelService.getAllTasks());
                updateLoadingState();
            }

            @Override
            public void modelReloadFailed(Throwable error) {
                isLoading.set(false);
                updateLoadingState();
            }
        });
    }

    private void updateLoadingState() {
        Platform.runLater(() -> {
            ObservableList<Node> children = getChildren();
            if (isLoading.get()) {
                if (!children.contains(loadingContainer)) {
                    children.clear();
                    children.add(loadingContainer);
                }
            } else {
                if (!children.contains(taskTreeView)) {
                    children.clear();
                    children.add(taskTreeView);
                }
            }
        });
    }

    @Getter
    public static abstract class GradleTaskTreeElement {
        private final String name;

        public GradleTaskTreeElement(String name) {
            this.name = name;
        }

        public abstract Ikon getIcon();

        public abstract String getStyleClass();

        public Tooltip getTooltip() {
            return null;
        }

        public ContextMenu getContextMenu() {
            return null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static class GradleProjectElement extends GradleTaskTreeElement {
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
            return "gradle-tasks-project-element";
        }

        @Override
        public ContextMenu getContextMenu() {
            return new GradleProjectContextMenu(this.project, this.gradleProject);
        }
    }

    public static class GradleTaskGroupElement extends GradleTaskTreeElement {
        public GradleTaskGroupElement(String name) {
            super("<no-group>".equals(name) ? "Other" : name);
        }

        @Override
        public Ikon getIcon() {
            return FontAwesomeSolid.FOLDER;
        }

        @Override
        public String getStyleClass() {
            return "gradle-tasks-group-element";
        }
    }

    @Getter
    public static class GradleTaskElement extends GradleTaskTreeElement {
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
            return "gradle-tasks-task-element";
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

    public static class GradleTaskTreeCell extends TreeCell<GradleTaskTreeElement> {
        private final FontIcon icon = new FontIcon();

        public GradleTaskTreeCell() {
            super();
            icon.setIconSize(16);
        }

        @Override
        protected void updateItem(GradleTaskTreeElement item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(item.getName());
                icon.getStyleClass().removeIf(styleClass ->
                    styleClass.equals("gradle-tasks-project-element") ||
                        styleClass.equals("gradle-tasks-group-element") ||
                        styleClass.equals("gradle-tasks-task-element")
                );
                icon.getStyleClass().add(item.getStyleClass());
                icon.setIconCode(item.getIcon());
                setGraphic(icon);
                setTooltip(item.getTooltip());
                setContextMenu(item.getContextMenu());
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty()) {
                        if (item instanceof GradleTaskElement taskElement) {
                            var runConfiguration = GradleTaskContextMenu.createRunConfig(
                                taskElement.getProject(),
                                taskElement.getTask()
                            );
                            runConfiguration.run(taskElement.getProject());
                        }
                    }
                });
            }
        }
    }
}
