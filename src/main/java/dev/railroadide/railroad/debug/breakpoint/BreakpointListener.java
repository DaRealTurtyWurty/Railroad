package dev.railroadide.railroad.debug.breakpoint;

@FunctionalInterface
public interface BreakpointListener {
    void onBreakpointEvent(BreakpointEvent event);
}
