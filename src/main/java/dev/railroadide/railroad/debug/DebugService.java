package dev.railroadide.railroad.debug;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.debug.breakpoint.SourceBreakpoint;
import dev.railroadide.railroad.debug.breakpoint.BreakpointEvent;
import dev.railroadide.railroad.debug.breakpoint.BreakpointListener;
import dev.railroadide.railroad.debug.breakpoint.BreakpointService;
import dev.railroadide.railroad.debug.jdi.JdiDebugSession;
import dev.railroadide.railroad.debug.model.DebugEndpoint;
import dev.railroadide.railroad.debug.model.DebugSessionEvent;
import dev.railroadide.railroad.debug.source.SourceResolver;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public final class DebugService {
    private final AtomicReference<JdiDebugSession> activeSession = new AtomicReference<>();

    public Optional<JdiDebugSession> getActiveSession() {
        return Optional.ofNullable(activeSession.get());
    }

    public CompletableFuture<JdiDebugSession> startSession(
        DebugEndpoint endpoint,
        SourceResolver sourceResolver,
        Collection<SourceBreakpoint> breakpoints,
        DebugSessionListener listener
    ) {
        return startSession(endpoint, sourceResolver, breakpoints, listener, null, _ -> true);
    }

    /**
     * Starts a session using the service's breakpoints and forwards subsequent matching changes.
     * Snapshotting, subscribing and queuing attachment are ordered with service mutations so
     * changes during attachment are applied after the initial breakpoint installation.
     *
     * @param endpoint debugger connection
     * @param sourceResolver mapping between runtime locations and source files
     * @param breakpointService shared breakpoint state to observe until the session ends
     * @param breakpointFilter selects breakpoints belonging to the debugged project
     * @param listener recipient of session events
     * @return future containing the attached session
     */
    public CompletableFuture<JdiDebugSession> startSession(
        DebugEndpoint endpoint,
        SourceResolver sourceResolver,
        BreakpointService breakpointService,
        Predicate<SourceBreakpoint> breakpointFilter,
        DebugSessionListener listener
    ) {
        synchronized (breakpointService) {
            var snapshot = breakpointService.getAll().stream().filter(breakpointFilter).toList();
            return startSession(endpoint, sourceResolver, snapshot, listener, breakpointService, breakpointFilter);
        }
    }

    private CompletableFuture<JdiDebugSession> startSession(
        DebugEndpoint endpoint,
        SourceResolver sourceResolver,
        Collection<SourceBreakpoint> breakpoints,
        DebugSessionListener listener,
        @Nullable BreakpointService breakpointService,
        Predicate<SourceBreakpoint> breakpointFilter
    ) {
        var session = new JdiDebugSession(
            endpoint,
            sourceResolver,
            List.copyOf(breakpoints));

        session.addListener(listener);

        if (!activeSession.compareAndSet(null, session))
            return CompletableFuture.failedFuture(
                new IllegalStateException(
                    "A debugging session is already active"));

        BreakpointListener breakpointListener = event -> forwardBreakpointEvent(session, event, breakpointFilter);
        if (breakpointService != null) {
            breakpointService.addListener(breakpointListener);
        }

        session.addListener(event -> {
            if (event instanceof DebugSessionEvent.Terminated) {
                activeSession.compareAndSet(session, null);
                if (breakpointService != null) {
                    breakpointService.removeListener(breakpointListener);
                }
            }
        });

        return session.attach()
            .thenApply(_ -> session)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    activeSession.compareAndSet(session, null);
                    if (breakpointService != null) {
                        breakpointService.removeListener(breakpointListener);
                    }
                }
            });
    }

    private void forwardBreakpointEvent(
        JdiDebugSession session,
        BreakpointEvent event,
        Predicate<SourceBreakpoint> breakpointFilter
    ) {
        SourceBreakpoint breakpoint = switch (event) {
            case BreakpointEvent.Added added -> added.breakpoint();
            case BreakpointEvent.Removed removed -> removed.breakpoint();
            case BreakpointEvent.Changed changed -> changed.breakpoint();
        };
        if (activeSession.get() != session || !breakpointFilter.test(breakpoint))
            return;

        CompletableFuture<Void> update = switch (event) {
            case BreakpointEvent.Added _ -> session.addBreakpoint(breakpoint);
            case BreakpointEvent.Removed _ -> session.removeBreakpoint(breakpoint.id());
            case BreakpointEvent.Changed _ -> session.updateBreakpoint(breakpoint);
        };
        update.exceptionally(throwable -> {
            if (activeSession.get() == session) {
                Railroad.LOGGER.error("Failed to synchronize debugger breakpoint", throwable);
            }
            return null;
        });
    }

    public CompletableFuture<Void> terminateActiveSession() {
        JdiDebugSession session = activeSession.get();
        if (session == null)
            return CompletableFuture.completedFuture(null);

        return session.terminate();
    }
}
