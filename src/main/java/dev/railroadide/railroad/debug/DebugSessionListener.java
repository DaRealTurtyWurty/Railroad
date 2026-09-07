package dev.railroadide.railroad.debug;

import dev.railroadide.railroad.debug.model.DebugSessionEvent;

@FunctionalInterface
public interface DebugSessionListener {
    void onDebugEvent(DebugSessionEvent event);
}
