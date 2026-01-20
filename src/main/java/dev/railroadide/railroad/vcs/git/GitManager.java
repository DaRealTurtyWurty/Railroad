package dev.railroadide.railroad.vcs.git;

import dev.railroadide.railroad.settings.Settings;

import java.nio.file.Path;
import java.util.Optional;

public final class GitManager {
    private GitManager() {
    }

    public static void loadGitExecutableIntoSettings() {
        Optional<Path> optionalPath = Settings.GIT_EXECUTABLE_PATH.getOptional();
        if (optionalPath.isEmpty()) {
            GitLocator.findGitExecutable().ifPresent(Settings.GIT_EXECUTABLE_PATH::setValue);
        }
    }
}
