package dev.railroadide.railroad.vcs.git.execution.progress;

public sealed interface GitProgressEvent {
    record Message(String text) implements GitProgressEvent {}
    record Phase(String name) implements GitProgressEvent {}
    record Percentage(String phase, int percent) implements GitProgressEvent {}
}
