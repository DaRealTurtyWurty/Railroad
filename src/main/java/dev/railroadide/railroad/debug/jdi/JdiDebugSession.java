package dev.railroadide.railroad.debug.jdi;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import dev.railroadide.railroad.debug.DebugSessionListener;
import dev.railroadide.railroad.debug.breakpoint.BreakpointManager;
import dev.railroadide.railroad.debug.breakpoint.SourceBreakpoint;
import dev.railroadide.railroad.debug.model.*;
import dev.railroadide.railroad.debug.source.DebugSource;
import dev.railroadide.railroad.debug.source.SourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class JdiDebugSession {
    private final DebugEndpoint endpoint;
    private final SourceResolver sourceResolver;
    private final Collection<SourceBreakpoint> initialBreakpoints;

    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(factory -> {
        var thread = new Thread(factory, "RailroadDebugCommands");
        thread.setDaemon(true);
        return thread;
    });

    private volatile DebugSessionState state = DebugSessionState.NEW;
    private final List<DebugSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong suspensionGeneration = new AtomicLong();

    private volatile VirtualMachine vm;
    private volatile boolean eventReaderRunning;
    private volatile Thread eventReaderThread;
    private volatile SuspendedContext suspendedContext;

    private BreakpointManager breakpointManager;
    private VariableInspector variableInspector;
    private ExpressionEvaluator expressionEvaluator;

    public JdiDebugSession(
        DebugEndpoint endpoint,
        SourceResolver sourceResolver,
        Collection<SourceBreakpoint> initialBreakpoints
    ) {
        this.endpoint = endpoint;
        this.sourceResolver = sourceResolver;
        this.initialBreakpoints = initialBreakpoints;
    }

    public CompletableFuture<Void> attach() {
        return submit(() -> {
            changeState(DebugSessionState.ATTACHING);
            vm = JdiConnections.attach(endpoint.host(), endpoint.port());
            changeState(DebugSessionState.CONFIGURING);

            variableInspector = new VariableInspector();
            expressionEvaluator = new ExpressionEvaluator();
            breakpointManager = new BreakpointManager(
                vm,
                sourceResolver);

            for (SourceBreakpoint breakpoint : initialBreakpoints) {
                breakpointManager.add(breakpoint);
            }

            startEventReader();

            return null;
        });
    }

    public CompletableFuture<Void> resume() {
        return submit(() -> {
            requireState(DebugSessionState.SUSPENDED);

            SuspendedContext context = this.suspendedContext;
            suspendedContext = null;
            variableInspector.endSuspension();
            changeState(DebugSessionState.RUNNING);

            if (context.eventSet() != null) {
                context.eventSet().resume();
            } else if (context.explicitVmSuspend()) {
                vm.resume();
            }

            fireEvent(new DebugSessionEvent.Resumed());

            return null;
        });
    }

    public CompletableFuture<Void> pause() {
        return submit(() -> {
            requireState(DebugSessionState.RUNNING);
            vm.suspend();

            List<ThreadReference> allThreads = vm.allThreads();
            ThreadReference selectedThread = choosePausedThread(allThreads);

            long generation = suspensionGeneration.incrementAndGet();

            Map<Long, ThreadReference> threads = new LinkedHashMap<>();
            for (ThreadReference thread : allThreads) {
                threads.put(thread.uniqueID(), thread);
            }

            suspendedContext = new SuspendedContext(
                generation,
                null,
                true,
                selectedThread,
                threads);

            variableInspector.beginSuspension(generation);
            changeState(DebugSessionState.SUSPENDED);
            fireEvent(new DebugSessionEvent.Suspended(
                DebugStopReason.PAUSE,
                selectedThread == null ? -1 : selectedThread.uniqueID()));

            return null;
        });
    }

    public CompletableFuture<Void> step(long threadId, DebugStepKind kind) {
        return submit(() -> {
            requireState(DebugSessionState.SUSPENDED);

            ThreadReference thread = requireThread(threadId);
            EventRequestManager requests = vm.eventRequestManager();

            List<StepRequest> existing = requests.stepRequests()
                .stream()
                .filter(req -> req.thread().equals(thread))
                .toList();
            if (!existing.isEmpty()) {
                requests.deleteEventRequests(existing);
            }

            int depth = switch (kind) {
                case INTO -> StepRequest.STEP_INTO;
                case OVER -> StepRequest.STEP_OVER;
                case OUT -> StepRequest.STEP_OUT;
            };

            // STEP_LINE means next different source line
            StepRequest request = requests.createStepRequest(thread, StepRequest.STEP_LINE, depth);
            addStepFilters(request);
            request.addCountFilter(1);
            request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            request.enable();

            resumeInternal();

            return null;
        });
    }

    public CompletableFuture<List<DebugThread>> threads() {
        return submit(() -> {
            requireState(DebugSessionState.SUSPENDED);

            return suspendedContext.threads().values().stream()
                .map(thread -> new DebugThread(
                    thread.uniqueID(),
                    thread.name(),
                    thread.status(),
                    thread.isSuspended()))
                .toList();
        });
    }

    public CompletableFuture<List<DebugFrame>> stackFrames(long threadId) {
        return submit(() -> {
            requireState(DebugSessionState.SUSPENDED);

            ThreadReference thread = requireThread(threadId);
            List<StackFrame> frames = thread.frames();

            List<DebugFrame> debugFrames = new ArrayList<>(frames.size());

            long generation = suspendedContext.generation();
            for (int index = 0; index < frames.size(); index++) {
                StackFrame frame = frames.get(index);
                Location location = frame.location();

                DebugSource source = sourceResolver.resolve(location)
                    .orElse(null);

                String name = location.declaringType().name() + "." + location.method().name();

                debugFrames.add(new DebugFrame(
                    new DebugFrameId(
                        generation,
                        threadId,
                        index),
                    name,
                    source,
                    location.lineNumber()));
            }

            return debugFrames;
        });
    }

    public CompletableFuture<List<DebugVariable>> variables(DebugFrameId frameId) {
        return submit(() -> {
            StackFrame frame = requireFrame(frameId);
            return variableInspector.inspectFrame(frame);
        });
    }

    public CompletableFuture<List<DebugVariable>> expandVariable(long reference, int start, int count) {
        return submit(() -> {
            requireState(DebugSessionState.SUSPENDED);

            return variableInspector.expand(
                reference,
                start,
                count);
        });
    }

    public CompletableFuture<DebugVariable> evaluate(DebugFrameId frameId, String expression) {
        return submit(() -> {
            StackFrame frame = requireFrame(frameId);

            Value value = expressionEvaluator.evaluate(
                frame,
                expression);

            return variableInspector.createVariable(
                expression,
                value == null
                    ? "null"
                    : value.type().name(),
                value);
        });
    }

    public CompletableFuture<Void> detach() {
        return submit(() -> {
            eventReaderRunning = false;

            if (eventReaderThread != null) {
                eventReaderThread.interrupt();
            }

            if (vm != null) {
                try {
                    vm.dispose();
                } catch (VMDisconnectedException _) {
                }
            }

            finishTerminated();

            return null;
        });
    }

    public CompletableFuture<Void> terminate() {
        return submit(() -> {
            if (state == DebugSessionState.TERMINATED)
                return null;

            changeState(DebugSessionState.TERMINATING);

            try {
                vm.exit(0);
            } catch (VMDisconnectedException _) {
            }

            finishTerminated();

            return null;
        });
    }

    public void addListener(DebugSessionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DebugSessionListener listener) {
        listeners.remove(listener);
    }

    public CompletableFuture<Void> addBreakpoint(SourceBreakpoint breakpoint) {
        return submit(() -> {
            breakpointManager.add(breakpoint);
            return null;
        });
    }

    public CompletableFuture<Void> removeBreakpoint(UUID breakpointId) {
        return submit(() -> {
            breakpointManager.remove(breakpointId);
            return null;
        });
    }

    public CompletableFuture<Void> updateBreakpoint(SourceBreakpoint breakpoint) {
        return submit(() -> {
            breakpointManager.remove(breakpoint.id());
            breakpointManager.add(breakpoint);
            return null;
        });
    }

    private void handleDisconnect() {
        finishTerminated();
    }

    private void finishTerminated() {
        if (state == DebugSessionState.TERMINATED)
            return;

        suspendedContext = null;

        if (variableInspector != null) {
            variableInspector.endSuspension();
        }

        eventReaderRunning = false;

        if (eventReaderThread != null) {
            eventReaderThread.interrupt();
        }

        changeState(DebugSessionState.TERMINATED);

        fireEvent(new DebugSessionEvent.Terminated());
    }

    private StackFrame requireFrame(DebugFrameId frameId) throws IncompatibleThreadStateException {
        requireState(DebugSessionState.SUSPENDED);
        if (frameId.suspensionGeneration() != suspendedContext.generation())
            throw new IllegalStateException("Stack frame belongs to an old suspension");

        ThreadReference thread = requireThread(frameId.threadId());
        return thread.frame(frameId.frameIndex());
    }

    private void resumeInternal() {
        SuspendedContext context = Objects.requireNonNull(suspendedContext);
        suspendedContext = null;
        variableInspector.endSuspension();

        changeState(DebugSessionState.RUNNING);

        if (context.eventSet() != null) {
            context.eventSet().resume();
        } else {
            vm.resume();
        }

        fireEvent(new DebugSessionEvent.Resumed());
    }

    // TODO: configurable
    private void addStepFilters(StepRequest request) {
        request.addClassExclusionFilter("java.*");
        request.addClassExclusionFilter("javax.*");
        request.addClassExclusionFilter("jdk.*");
        request.addClassExclusionFilter("sun.*");
        request.addClassExclusionFilter("com.sun.*");
    }

    private ThreadReference requireThread(long threadId) {
        return this.vm.allThreads().stream()
            .filter(thread -> thread != null && thread.uniqueID() == threadId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Thread " + threadId + " not found"));
    }

    private ThreadReference choosePausedThread(List<ThreadReference> threads) {
        return threads.stream()
            .filter(ThreadReference::isSuspended)
            .findFirst()
            .orElse(null);
    }

    private void requireState(@Nullable DebugSessionState state) {
        if (this.state != state)
            throw new IllegalStateException("Expected debugger state " + state + " but got " + this.state);
    }

    private void startEventReader() {
        eventReaderRunning = true;

        eventReaderThread = new Thread(
            this::pollForEvents,
            "RailroadDebugEvents");
        eventReaderThread.setDaemon(true);
        eventReaderThread.start();
    }

    private void pollForEvents() {
        EventQueue eventQueue = vm.eventQueue();
        while (eventReaderRunning) {
            try {
                EventSet eventSet = eventQueue.remove();
                commandExecutor.execute(() -> handleEventSet(eventSet));
            } catch (InterruptedException _) {
            } catch (VMDisconnectedException _) {
                commandExecutor.execute(this::handleDisconnect);
                return;
            }
        }
    }

    private void handleEventSet(EventSet eventSet) {
        boolean terminate = false;
        ThreadReference stopThread = null;
        DebugStopReason stopReason = null;

        try {
            for (Event event : eventSet) {
                switch (event) {
                    case ClassPrepareEvent prepareEvent -> breakpointManager.onClassPrepared(prepareEvent);
                    case BreakpointEvent breakpointEvent -> {
                        stopThread = breakpointEvent.thread();
                        stopReason = DebugStopReason.BREAKPOINT;
                    }
                    case StepEvent stepEvent -> {
                        cleanupStepEvent(stepEvent);

                        if (stopReason == null) {
                            stopThread = stepEvent.thread();
                            stopReason = DebugStopReason.STEP;
                        }
                    }
                    case VMStartEvent _ -> {
                        if (state == DebugSessionState.CONFIGURING) {
                            changeState(DebugSessionState.RUNNING);
                        }
                    }
                    case VMDeathEvent _,VMDisconnectEvent _ -> terminate = true;
                    case null, default -> {
                    }
                }
            }

            if (terminate) {
                finishTerminated();
                return;
            }

            if (stopThread != null) {
                suspendFromEvent(
                    eventSet,
                    stopThread,
                    stopReason);
            } else {
                eventSet.resume();
                if (state == DebugSessionState.CONFIGURING) {
                    changeState(DebugSessionState.RUNNING);
                }
            }
        } catch (Throwable throwable) {
            fireEvent(new DebugSessionEvent.Error(
                "Debugger event processing failed",
                throwable));

            try {
                eventSet.resume();
            } catch (Throwable _) {
            }
        }
    }

    private void suspendFromEvent(EventSet eventSet, ThreadReference eventThread, DebugStopReason reason) {
        long generation = suspensionGeneration.incrementAndGet();
        Map<Long, ThreadReference> threads = captureThreads(eventThread);

        suspendedContext = new SuspendedContext(
            generation,
            eventSet,
            false,
            eventThread,
            threads);

        variableInspector.beginSuspension(generation);
        changeState(DebugSessionState.SUSPENDED);
        fireEvent(new DebugSessionEvent.Suspended(
            reason,
            eventThread.uniqueID()));
    }

    private Map<Long, ThreadReference> captureThreads(ThreadReference eventThread) {
        Map<Long, ThreadReference> threads = new LinkedHashMap<>();
        for (ThreadReference thread : vm.allThreads()) {
            threads.put(thread.uniqueID(), thread);
        }

        if (eventThread != null) {
            threads.put(eventThread.uniqueID(), eventThread);
        }

        return threads;
    }

    private void cleanupStepEvent(StepEvent stepEvent) {
        EventRequest request = stepEvent.request();
        if (request != null) {
            try {
                vm.eventRequestManager().deleteEventRequest(request);
            } catch (RuntimeException _) {
            }
        }
    }

    private void changeState(@NotNull DebugSessionState newState) {
        DebugSessionState oldState = state;
        state = newState;
        fireEvent(new DebugSessionEvent.StateChanged(oldState, state));
    }

    private void fireEvent(@NotNull DebugSessionEvent event) {
        for (DebugSessionListener listener : listeners) {
            listener.onDebugEvent(event);
        }
    }

    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        commandExecutor.submit(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
}
