package dev.railroadide.railroad.ide.diagnostics;

/** The stage that produced a diagnostic, independently of its provider or diagnostic code. */
public enum DiagnosticOrigin {
    SYNTAX,
    SEMANTIC,
    BUILD,
    INSPECTION
}
