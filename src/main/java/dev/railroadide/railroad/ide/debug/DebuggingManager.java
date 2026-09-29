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
import lombok.Getter;

import java.nio.file.Files;
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
    private long suspendedThread = -1;
    private long revision;
    private TextEditorPane executionEditor;

    /**
     * Attaches a debugger and publishes its state on the JavaFX thread for this project.
     *
     * @param endpoint    debuggee address
     * @param resolver    source mapping
     * @param breakpoints shared breakpoint store
     * @param filter      project breakpoint filter
     * @param listener    additional debug event recipient
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

    private void acceptEvent(CompletableFuture<JdiDebugSession> pending, DebugSessionEvent event) {
        if (connection != pending)
            return;
        if (event instanceof DebugSessionEvent.StateChanged changed) {
            state.set(changed.newState());
            active.set(
                changed.newState() != DebugSessionState.TERMINATED && changed.newState() != DebugSessionState.FAILED);
            if (changed.newState() != DebugSessionState.SUSPENDED) {
                paused.set(false);
                suspendedThread = -1;
                location.set("");
                clearExecutionLine();
                revision++;
            }
        } else if (event instanceof DebugSessionEvent.Suspended suspended) {
            suspendedThread = suspended.threadId();
            paused.set(true);
            long currentRevision = ++revision;
            pending.thenCompose(session -> session.stackFrames(suspended.threadId()))
                .thenAccept(frames -> Platform.runLater(() -> {
                    if (connection == pending && revision == currentRevision && !frames.isEmpty()) {
                        var frame = frames.getFirst();
                        location.set(frame.displayName() + ":" + frame.line());
                        showExecutionLine(frame, pending, currentRevision);
                    }
                })).exceptionally(_ -> null);
        } else if (event instanceof DebugSessionEvent.Terminated) {
            connection = null;
            state.set(DebugSessionState.TERMINATED);
            active.set(false);
            paused.set(false);
            commandPending.set(false);
            suspendedThread = -1;
            location.set("");
            clearExecutionLine();
            revision++;
        }
    }

    private void showExecutionLine(DebugFrame frame, CompletableFuture<JdiDebugSession> pending, long currentRevision) {
        clearExecutionLine();
        if (Services.IDE_STATE.getCurrentProject() != project || frame.line() < 1
            || !(frame.source() instanceof DebugSource.FileSource source) || !Files.isRegularFile(source.file()))
            return;

        Services.EDITOR_TAB_MANAGER.open(source.file());
        // Newly opened editors queue their initial caret placement during construction.
        Platform.runLater(() -> {
            if (connection != pending || revision != currentRevision
                || Services.IDE_STATE.getCurrentProject() != project)
                return;
            Services.EDITOR_TAB_MANAGER.activeTab()
                .filter(
                    tab -> tab.path().toAbsolutePath().normalize().equals(source.file().toAbsolutePath().normalize()))
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
        return canResume() && suspendedThread >= 0;
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
     * Steps the thread selected by the most recent suspension.
     *
     * @param kind step depth
     */
    public void step(DebugStepKind kind) {
        if (canStep()) {
            long thread = suspendedThread;
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
