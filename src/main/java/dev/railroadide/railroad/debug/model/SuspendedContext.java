package dev.railroadide.railroad.debug.model;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.EventSet;

import java.util.Map;

public record SuspendedContext(
    long generation,
    EventSet eventSet,
    boolean explicitVmSuspend,
    ThreadReference eventThread,
    Map<Long, ThreadReference> threads
) {
}
