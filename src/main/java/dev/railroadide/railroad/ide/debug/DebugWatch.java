package dev.railroadide.railroad.ide.debug;

import java.util.UUID;

/**
 * A project watch expression, retained across debug sessions.
 */
public record DebugWatch(UUID id, String expression) {
}
