package dev.railroadide.railroad.vcs.git.identity;

public record GitIdentity(
    String userName,
    String email,
    GitSigningStatus signing,
    String gitVersion
) {}
