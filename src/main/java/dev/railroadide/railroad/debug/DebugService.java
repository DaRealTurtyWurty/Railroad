package dev.railroadide.railroad.debug;

import dev.railroadide.railroad.debug.breakpoint.SourceBreakpoint;
import dev.railroadide.railroad.debug.jdi.JdiDebugSession;
import dev.railroadide.railroad.debug.model.DebugEndpoint;
import dev.railroadide.railroad.debug.model.DebugSessionEvent;
import dev.railroadide.railroad.debug.source.SourceResolver;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class DebugService {
    private final AtomicReference<JdiDebugSession> activeSession = new AtomicReference<>();
    private final SourceResolver sourceResolver;

    public DebugService(SourceResolver sourceResolver) {
        this.sourceResolver = sourceResolver;
    }

    public Optional<JdiDebugSession> getActiveSession() {
        return Optional.ofNullable(activeSession.get());
    }

    public CompletableFuture<JdiDebugSession> startSession(
        DebugEndpoint endpoint,
        Collection<SourceBreakpoint> breakpoints
    ) {
        JdiDebugSession session = new JdiDebugSession(
            endpoint,
            sourceResolver,
            breakpoints
        );

        if (!activeSession.compareAndSet(null, session)) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(
                    "A debugging session is already active"
                )
            );
        }

        session.addListener(event -> {
            if (event instanceof DebugSessionEvent.Terminated) {
                activeSession.compareAndSet(session, null);
            }
        });

        return session.attach()
            .thenApply(ignored -> session)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    activeSession.compareAndSet(session, null);
                }
            });
    }

    public CompletableFuture<Void> terminateActiveSession() {
        JdiDebugSession session = activeSession.get();
        if (session == null)
            return CompletableFuture.completedFuture(null);

        return session.terminate();
    }
}
