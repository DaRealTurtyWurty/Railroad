package dev.railroadide.railroad.debug.breakpoint;

public sealed interface BreakpointEvent {
    record Added(SourceBreakpoint breakpoint) implements BreakpointEvent {
    }

    record Removed(SourceBreakpoint breakpoint) implements BreakpointEvent {
    }

    record Changed(SourceBreakpoint breakpoint) implements BreakpointEvent {
    }
}
