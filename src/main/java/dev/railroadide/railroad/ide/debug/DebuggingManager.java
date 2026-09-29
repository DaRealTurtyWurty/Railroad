package dev.railroadide.railroad.ide.debug;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.debug.DebugSessionListener;
import dev.railroadide.railroad.debug.breakpoint.BreakpointService;
import dev.railroadide.railroad.debug.breakpoint.SourceBreakpoint;
import dev.railroadide.railroad.debug.jdi.JdiDebugSession;
import dev.railroadide.railroad.debug.model.*;
import dev.railroadide.railroad.debug.source.DebugSource;
import dev.railroadide.railroad.debug.source.SourceResolver;
import dev.railroadide.railroad.ide.ui.codeeditor.TextEditorPane;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Holds observable active and paused debug state for a project.
 */
public final class DebuggingManager {
    private final BooleanProperty active = new SimpleBooleanProperty(this, "active", false);
    private final BooleanProperty paused = new SimpleBooleanProperty(this, "paused", false);
    @Getter
    private final BooleanProperty commandPending = new SimpleBooleanProperty(this, "commandPending", false);
    @Getter
    private final StringProperty location = new SimpleStringProperty(this, "location", "");
    @Getter
    private final ObjectProperty<DebugSessionState> state = new SimpleObjectProperty<>(DebugSessionState.NEW);
    private volatile CompletableFuture<JdiDebugSession> connection;

    private final ObservableList<DebugThread> threads = FXCollections.observableArrayList();
    private final ObservableList<DebugFrame> frames = FXCollections.observableArrayList();
    private final ObservableList<DebugVariable> variables = FXCollections.observableArrayList();
    private final ObjectProperty<DebugThread> selectedThread = new SimpleObjectProperty<>();
    private final ObjectProperty<DebugFrame> selectedFrame = new SimpleObjectProperty<>();

    private final ObservableList<DebugWatch> watches = FXCollections.observableArrayList();
    private final ObservableList<WatchResult> watchResults = FXCollections.observableArrayList();
    private final StringProperty inspectionError = new SimpleStringProperty("");
    private long selectionRevision;
    private long watchRevision;
    private long revision;
    private TextEditorPane executionEditor;

    /**
     * Attaches a debugger and publishes its state on the JavaFX thread for this project.
     *
     * @param endpoint debuggee address
     * @param resolver source mapping
     * @param breakpoints shared breakpoint store
     * @param filter project breakpoint filter
     * @param listener additional debug event recipient
     * @return attached session
     */
    public synchronized CompletableFuture<JdiDebugSession> startSession(
        DebugEndpoint endpoint,
        SourceResolver resolver,
        BreakpointService breakpoints,
        Predicate<SourceBreakpoint> filter,
        DebugSessionListener listener
    ) {
        if (connection != null)
            return CompletableFuture.failedFuture(new IllegalStateException("This project is already debugging"));
        var pending = new CompletableFuture<JdiDebugSession>();
        connection = pending;
        Services.DEBUG_SERVICE.startSession(endpoint, resolver, breakpoints, filter, event -> {
            Platform.runLater(() -> acceptEvent(pending, event));
            listener.onDebugEvent(event);
        }).whenComplete((session, failure) -> {
            if (failure == null) {
                pending.complete(session);
            } else {
                pending.completeExceptionally(failure);
                Platform.runLater(() -> acceptEvent(pending, new DebugSessionEvent.Terminated()));
            }
        });
        return pending;
    }

    public ObservableList<DebugThread> getThreads() {
        return FXCollections.unmodifiableObservableList(threads);
    }

    public ObservableList<DebugFrame> getFrames() {
        return FXCollections.unmodifiableObservableList(frames);
    }

    public ObservableList<DebugVariable> getVariables() {
        return FXCollections.unmodifiableObservableList(variables);
    }

    public ObservableList<WatchResult> getWatchResults() {
        return FXCollections.unmodifiableObservableList(watchResults);
    }

    public ReadOnlyStringProperty inspectionErrorProperty() {
        return inspectionError;
    }

    public void addWatch(String expression) {
        if (expression == null || expression.isBlank())
            return;
        watches.add(new DebugWatch(UUID.randomUUID(), expression.trim()));
        refreshWatches();
    }

    public void removeWatch(DebugWatch watch) {
        watches.remove(watch);
        refreshWatches();
    }

    private void refreshWatches() {
        long request = ++watchRevision;
        watchResults.setAll(watches.stream().map(watch -> new WatchResult(watch, null, null)).toList());
        DebugFrame frame = selectedFrame.get();
        if (!isPaused() || frame == null)
            return;
        var pending = connection;
        long currentRevision = revision;
        for (DebugWatch watch : List.copyOf(watches)) {
            pending.thenCompose(session -> session.evaluate(frame.id(), watch.expression()))
                .whenComplete((value, failure) -> Platform.runLater(() -> {
                    if (!isCurrent(pending, currentRevision) || request != watchRevision)
                        return;
                    int index = watches.indexOf(watch);
                    if (index >= 0) {
                        watchResults.set(index, new WatchResult(watch, value,
                            failure == null ? null : failureMessage(failure)));
                    }
                }));
        }
    }

    public CompletableFuture<List<DebugVariable>> expandVariable(long reference, int start, int count) {
        if (!isPaused() || selectedFrame.get() == null)
            return CompletableFuture.failedFuture(new IllegalStateException("No suspended frame selected"));
        CompletableFuture<JdiDebugSession> pending = connection;
        long currentRevision = revision;
        long selection = selectionRevision;
        var result = new CompletableFuture<List<DebugVariable>>();
        pending.thenCompose(session -> session.expandVariable(reference, start, count))
            .whenComplete((children, failure) -> Platform.runLater(() -> {
                if (!isCurrent(pending, currentRevision) || selection != selectionRevision) {
                    result.completeExceptionally(new CancellationException("Debugger selection changed"));
                } else if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.complete(children);
                }
            }));
        return result;
    }

    private static String failureMessage(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private void clearInspection() {
        revision++;
        selectionRevision++;
        selectedFrame.set(null);
        selectedThread.set(null);
        threads.clear();
        frames.clear();
        variables.clear();
        inspectionError.set("");
        location.set("");
        clearExecutionLine();
        refreshWatches();
    }

    public ReadOnlyObjectProperty<DebugThread> selectedThreadProperty() {
        return selectedThread;
    }

    public ReadOnlyObjectProperty<DebugFrame> selectedFrameProperty() {
        return selectedFrame;
    }

    private void acceptEvent(CompletableFuture<JdiDebugSession> pending, DebugSessionEvent event) {
        if (connection != pending)
            return;

        if (event instanceof DebugSessionEvent.StateChanged changed) {
            state.set(changed.newState());
            active.set(
                changed.newState() != DebugSessionState.TERMINATED && changed.newState() != DebugSessionState.FAILED);
            if (changed.newState() != DebugSessionState.SUSPENDED) {
                paused.set(false);
                clearInspection();
            }
        } else if (event instanceof DebugSessionEvent.Suspended suspended) {
            handleSuspended(pending, suspended);
        } else if (event instanceof DebugSessionEvent.Terminated) {
            connection = null;
            state.set(DebugSessionState.TERMINATED);
            active.set(false);
            paused.set(false);
            commandPending.set(false);
            clearInspection();
        }
    }

    private void handleSuspended(CompletableFuture<JdiDebugSession> pending, DebugSessionEvent.Suspended suspended) {
        clearInspection();
        paused.set(true);
        long currentRevision = revision;
        pending.thenCompose(JdiDebugSession::threads).whenComplete((result, failure) -> Platform.runLater(() -> {
            if (!isCurrent(pending, currentRevision))
                return;
            if (failure != null) {
                inspectionError.set(failureMessage(failure));
                return;
            }
            threads.setAll(result);
            selectThread(result.stream().filter(thread -> thread.id() == suspended.threadId())
                .findFirst().orElseGet(() -> result.stream().filter(DebugThread::suspended).findFirst().orElse(null)));
        }));
    }

    public void selectThread(DebugThread thread) {
        if (!isPaused() || selectedThread.get() == thread || (thread != null && !threads.contains(thread)))
            return;
        long selection = ++selectionRevision;
        selectedThread.set(thread);
        selectedFrame.set(null);
        frames.clear();
        variables.clear();
        location.set("");
        inspectionError.set("");
        clearExecutionLine();
        refreshWatches();
        if (thread == null || !thread.suspended())
            return;
        CompletableFuture<JdiDebugSession> pending = connection;
        long currentRevision = revision;
        pending.thenCompose(session -> session.stackFrames(thread.id()))
            .whenComplete((result, failure) -> Platform.runLater(() -> {
                if (!isCurrent(pending, currentRevision) || selection != selectionRevision)
                    return;
                if (failure != null) {
                    inspectionError.set(failureMessage(failure));
                    return;
                }
                frames.setAll(result);
                if (!result.isEmpty()) {
                    selectFrame(result.getFirst());
                }
            }));
    }

    public void selectFrame(DebugFrame frame) {
        if (!isPaused() || selectedFrame.get() == frame || (frame != null && !frames.contains(frame)))
            return;
        long selection = ++selectionRevision;
        selectedFrame.set(frame);
        variables.clear();
        inspectionError.set("");
        clearExecutionLine();
        location.set(frame == null ? "" : frame.displayName() + ":" + frame.line());
        refreshWatches();
        if (frame == null)
            return;
        CompletableFuture<JdiDebugSession> pending = connection;
        long currentRevision = revision;
        showExecutionLine(frame, pending, currentRevision);
        pending.thenCompose(session -> session.variables(frame.id()))
            .whenComplete((result, failure) -> Platform.runLater(() -> {
                if (!isCurrent(pending, currentRevision) || selection != selectionRevision)
                    return;
                if (failure != null) {
                    inspectionError.set(failureMessage(failure));
                } else {
                    variables.setAll(result);
                }
            }));
    }

    private boolean isCurrent(CompletableFuture<JdiDebugSession> pending, long currentRevision) {
        return connection == pending && revision == currentRevision && isPaused();
    }

    private void showExecutionLine(DebugFrame frame, CompletableFuture<JdiDebugSession> pending, long currentRevision) {
        clearExecutionLine();
        if (Services.IDE_STATE.getCurrentProject() != project || frame.line() < 1
            || !(frame.source() instanceof DebugSource.FileSource(Path file)) || !Files.isRegularFile(file))
            return;

        Services.EDITOR_TAB_MANAGER.open(file);
        // Newly opened editors queue their initial caret placement during construction.
        Platform.runLater(() -> {
            if (!isCurrent(pending, currentRevision) || selectedFrame.get() != frame
                || Services.IDE_STATE.getCurrentProject() != project)
                return;
            Services.EDITOR_TAB_MANAGER.activeTab()
                .filter(
                    tab -> tab.path().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize()))
                .ifPresent(tab -> {
                    executionEditor = tab.view().activeEditor();
                    if (executionEditor != null) {
                        executionEditor.showExecutionLine(frame.line());
                    }
                });
        });
    }

    private void clearExecutionLine() {
        if (executionEditor != null) {
            executionEditor.clearExecutionLine();
            executionEditor = null;
        }
    }

    /**
     * Checks whether a suspended session can accept a resume command.
     *
     * @return whether resume is available
     */
    public boolean canResume() {
        return active.get() && paused.get() && !commandPending.get();
    }

    /**
     * Checks whether the attached session is running and ready to pause.
     *
     * @return whether pause is available
     */
    public boolean canPause() {
        return state.get() == DebugSessionState.RUNNING && !commandPending.get() && connection != null
            && connection.isDone() && !connection.isCompletedExceptionally();
    }

    /**
     * Checks whether the suspended session has a thread that can be stepped.
     *
     * @return whether stepping is available
     */
    public boolean canStep() {
        return canResume() && selectedThread.get() != null && selectedThread.get().suspended();
    }

    /**
     * Resumes the project's suspended debuggee.
     */
    public void resume() {
        if (canResume()) {
            submit(JdiDebugSession::resume);
        }
    }

    /**
     * Pauses the project's running debuggee.
     */
    public void pause() {
        if (canPause()) {
            submit(JdiDebugSession::pause);
        }
    }

    /**
     * Steps the currently selected suspended thread.
     *
     * @param kind step depth
     */
    public void step(DebugStepKind kind) {
        if (canStep()) {
            long thread = selectedThread.get().id();
            submit(session -> session.step(thread, kind));
        }
    }

    private void submit(Function<JdiDebugSession, CompletableFuture<Void>> action) {
        var pending = connection;
        commandPending.set(true);
        pending.thenCompose(action).whenComplete((_, failure) -> Platform.runLater(() -> {
            if (connection == pending) {
                commandPending.set(false);
            }
            if (failure != null) {
                Railroad.LOGGER.error("Debugger command failed", failure);
            }
        }));
    }

    @Getter
    private final Project project;

    /**
     * Creates debug state for a project with both flags initially false.
     *
     * @param project project whose editor features or debug state are managed
     */
    public DebuggingManager(Project project) {
        this.project = project;
    }

    /**
     * Checks the current debug active flag.
     *
     * @return whether debugging is active
     */
    public boolean isActive() {
        return active.get();
    }

    /**
     * Checks the current debug paused flag.
     *
     * @return whether debugging is paused
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Exposes the mutable debug active flag.
     *
     * @return observable active state
     */
    public BooleanProperty activeProperty() {
        return active;
    }

    /**
     * Exposes the mutable debug paused flag.
     *
     * @return observable paused state
     */
    public BooleanProperty pausedProperty() {
        return paused;
    }
}
