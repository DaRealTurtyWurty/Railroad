package dev.railroadide.railroad.vcs.git;

public record GitIdentity(
    String userName,
    String email,
    SigningStatus signing,
    String gitVersion
) {}
