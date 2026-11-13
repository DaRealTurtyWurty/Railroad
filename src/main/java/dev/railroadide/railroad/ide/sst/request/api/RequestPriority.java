package dev.railroadide.railroad.ide.sst.request.api;

/**
 * Shared scheduling order for language work, from most to least interactive.
 * Priority orders queued requests; it does not preempt a running provider. Requests
 * with equal priority execute in submission order when a worker becomes available.
 */
public enum RequestPriority {
    COMPLETION,
    SIGNATURE_HELP,
    VISIBLE_DIAGNOSTICS,
    OPEN_DOCUMENT_ANALYSIS,
    BACKGROUND_INDEXING
}
