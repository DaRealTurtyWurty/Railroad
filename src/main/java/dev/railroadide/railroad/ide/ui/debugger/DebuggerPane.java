package dev.railroadide.railroad.ide.ui.debugger;

import dev.railroadide.railroad.debug.model.DebugFrame;
import dev.railroadide.railroad.debug.model.DebugThread;
import dev.railroadide.railroad.debug.model.DebugVariable;
import dev.railroadide.railroad.ide.debug.DebuggingManager;
import dev.railroadide.railroad.ide.debug.WatchResult;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.ui.RRButton;
import dev.railroadide.railroad.ui.RRListView;
import dev.railroadide.railroad.ui.localized.LocalizedLabel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Project debugger inspection view. All backend access goes through the manager.
 */
public final class DebuggerPane extends BorderPane implements AutoCloseable {
    private final DebuggingManager debugging;
    private final RRListView<DebugThread> threads = new RRListView<>();
    private final RRListView<DebugFrame> frames = new RRListView<>();
    private final TreeView<VariableNode> variables = new TreeView<>(new TreeItem<>());
    private final TreeView<VariableNode> watches = new TreeView<>(new TreeItem<>());
    private final List<Subscription> subscriptions = new ArrayList<>();
    private boolean syncing;
    private boolean closed;
    private long treeRevision;

    public DebuggerPane(Project project) {
        debugging = project.getDebuggingManager();
        setPadding(new Insets(6));
        threads.setItems(debugging.getThreads());
        frames.setItems(debugging.getFrames());
        threads.setCellFactory(_ -> cell(thread -> thread.name() + " (#" + thread.id() + ")"));
        frames.setCellFactory(_ -> cell(frame -> frame.displayName() + "():" + frame.line()));
        threads.setPlaceholder(new LocalizedLabel("railroad.debugger.no_threads"));
        frames.setPlaceholder(new LocalizedLabel("railroad.debugger.no_frames"));
        configureTree(variables);
        configureTree(watches);
        var expression = new TextField();
        expression.setAccessibleText("Watch expression");
        var add = new RRButton("railroad.debugger.add_watch");
        Runnable addWatch = () -> {
            debugging.addWatch(expression.getText());
            expression.clear();
        };
        add.setOnAction(_ -> addWatch.run());
        expression.setOnAction(_ -> addWatch.run());
        var remove = new RRButton("railroad.debugger.remove_watch");
        remove.setOnAction(_ -> {
            TreeItem<VariableNode> item = watches.getSelectionModel().getSelectedItem();
            while (item != null && item.getParent() != watches.getRoot()) {
                item = item.getParent();
            }
            if (item != null && item.getValue().watch != null) {
                debugging.removeWatch(item.getValue().watch.watch());
            }
        });
        var watchActions = new HBox(6, expression, add, remove);
        HBox.setHgrow(expression, Priority.ALWAYS);
        var watchPane = new BorderPane(watches);
        watchPane.setBottom(watchActions);
        var left = new SplitPane(section("threads", threads), section("call_stack", frames));
        left.setOrientation(Orientation.VERTICAL);
        var right = new SplitPane(section("variables", variables), section("watches", watchPane));
        right.setOrientation(Orientation.VERTICAL);
        var split = new SplitPane(left, right);
        split.setDividerPositions(0.32);
        setCenter(split);
        var status = new Label();
        var error = new Label();
        error.textProperty().bind(debugging.inspectionErrorProperty());
        setTop(new VBox(3, status, error));
        subscriptions.add(debugging.getState().subscribe(state -> status.setText(state.name())));
        subscriptions.add(threads.getSelectionModel().selectedItemProperty().subscribe((_, thread) -> {
            if (!syncing && thread != null) {
                debugging.selectThread(thread);
            }
        }));
        subscriptions.add(frames.getSelectionModel().selectedItemProperty().subscribe((_, frame) -> {
            if (!syncing && frame != null) {
                debugging.selectFrame(frame);
            }
        }));
        subscriptions.add(debugging.selectedThreadProperty().subscribe(thread -> {
            syncing = true;
            threads.getSelectionModel().select(thread);
            syncing = false;
        }));
        subscriptions.add(debugging.selectedFrameProperty().subscribe(frame -> {
            treeRevision++;
            syncing = true;
            frames.getSelectionModel().select(frame);
            syncing = false;
        }));
        subscriptions.add(debugging.getVariables().subscribe(this::refreshVariables));
        subscriptions.add(debugging.getWatchResults().subscribe(this::refreshWatches));
        refreshVariables();
        refreshWatches();
    }

    private static BorderPane section(String key, Node content) {
        var pane = new BorderPane(content);
        pane.setTop(new LocalizedLabel("railroad.debugger." + key));
        pane.setPadding(new Insets(4));
        pane.setMinSize(0, 0);
        return pane;
    }

    private static <T> ListCell<T> cell(Function<T, String> formatter) {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatter.apply(item));
            }
        };
    }

    private void configureTree(TreeView<VariableNode> tree) {
        tree.setShowRoot(false);
        tree.getRoot().setExpanded(true);
        tree.setCellFactory(_ -> new TreeCell<>() {
            @Override
            protected void updateItem(VariableNode node, boolean empty) {
                super.updateItem(node, empty);
                setText(empty || node == null ? null : node.text);
            }
        });
    }

    private void refreshVariables() {
        variables.getRoot().getChildren().setAll(debugging.getVariables().stream()
            .map(value -> variableItem(value, null)).toList());
    }

    private void refreshWatches() {
        watches.getRoot().getChildren().setAll(debugging.getWatchResults().stream().map(result -> {
            if (result.value() != null)
                return variableItem(result.value(), result);
            String value = result.error() == null ? "?" : "<" + result.error() + ">";
            return new TreeItem<>(new VariableNode(null, result, result.watch().expression() + " = " + value));
        }).toList());
    }

    private TreeItem<VariableNode> variableItem(DebugVariable value, WatchResult watch) {
        var node = new VariableNode(value, watch, value.name() + " = " + value.value() + "  : " + value.type());
        var item = new TreeItem<>(node);
        if (value.hasChildren()) {
            item.getChildren().add(new TreeItem<>(new VariableNode(null, null, "?")));
            item.expandedProperty().addListener((_, _, expanded) -> {
                if (expanded && !node.loaded) {
                    node.loaded = true;
                    loadChildren(item, value, 0);
                }
            });
        }
        return item;
    }

    private void loadChildren(TreeItem<VariableNode> item, DebugVariable value, int start) {
        long request = treeRevision;
        debugging.expandVariable(value.childrenReference(), start, 100)
            .whenComplete((children, failure) -> Platform.runLater(() -> {
                if (closed || request != treeRevision)
                    return;
                if (failure != null) {
                    Throwable cause = failure;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    item.getChildren()
                        .setAll(new TreeItem<>(new VariableNode(null, null, "<" + cause.getMessage() + ">")));
                    item.getValue().loaded = false;
                    return;
                }
                item.getChildren().setAll(children.stream().map(child -> variableItem(child, null)).toList());
                int next = start + children.size();
                if (next < value.indexedChildren() && !children.isEmpty()) {
                    var more = new TreeItem<>(new VariableNode(null, null, "[" + next + "?]"));
                    more.getChildren().add(new TreeItem<>(new VariableNode(null, null, "?")));
                    more.expandedProperty().addListener((_, _, expanded) -> {
                        if (expanded && !more.getValue().loaded) {
                            more.getValue().loaded = true;
                            loadChildren(more, value, next);
                        }
                    });
                    item.getChildren().add(more);
                }
            }));
    }

    @Override
    public void close() {
        closed = true;
        subscriptions.forEach(Subscription::unsubscribe);
        subscriptions.clear();
        threads.setItems(null);
        frames.setItems(null);
    }

    private static final class VariableNode {
        private final DebugVariable variable;
        private final WatchResult watch;
        private final String text;
        private boolean loaded;

        private VariableNode(DebugVariable variable, WatchResult watch, String text) {
            this.variable = variable;
            this.watch = watch;
            this.text = text;
        }
    }
}
