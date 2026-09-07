package dev.railroadide.railroad.debug.model;

import dev.railroadide.railroad.plugin.spi.event.Event;

public sealed interface DebugSessionEvent extends Event {
    record StateChanged(DebugSessionState oldState, DebugSessionState newState) implements DebugSessionEvent {
    }

    record Suspended(DebugStopReason reason, long threadId) implements DebugSessionEvent {
    }

    record Resumed() implements DebugSessionEvent {
    }

    record Error(String message, Throwable cause) implements DebugSessionEvent {
    }

    record Terminated() implements DebugSessionEvent {
    }
}
