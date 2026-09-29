package dev.railroadide.railroad.ide.debug;

import dev.railroadide.railroad.debug.model.DebugVariable;

/**
 * A watch value or inline evaluation error for the selected frame.
 */
public record WatchResult(DebugWatch watch, DebugVariable value, String error) {
}
