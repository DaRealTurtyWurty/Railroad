package dev.railroadide.railroad.vcs.git.identity;

import org.jspecify.annotations.NonNull;

import java.util.StringJoiner;

public record GitSigningStatus(
    boolean enabled,
    Format format,
    String signingKey
) {
    public static GitSigningStatus fromGitConfigValues(String gpgSignSetting, String gpgFormatSetting, String userSigningKey, String gpgProgram) {
        boolean enabled = "true".equalsIgnoreCase(gpgSignSetting) || "always".equalsIgnoreCase(gpgSignSetting);
        Format format;
        if ("openpgp".equalsIgnoreCase(gpgFormatSetting)) {
            format = Format.OPENPGP;
        } else if ("ssh".equalsIgnoreCase(gpgFormatSetting)) {
            format = Format.SSH;
        } else {
            format = Format.UNKNOWN;
        }

        String signingKey = (userSigningKey != null && !userSigningKey.isBlank()) ? userSigningKey : null;

        return new GitSigningStatus(enabled, format, signingKey);
    }

    public enum Format {
        OPENPGP,
        SSH,
        UNKNOWN
    }

    @Override
    public @NonNull String toString() {
        if (!enabled)
            return "Disabled";

        var joiner = new StringJoiner(", ", "Enabled (", ")");
        joiner.add("Format: " + format);
        if (signingKey != null && !signingKey.isBlank()) {
            joiner.add("Key: " + signingKey);
        } else {
            joiner.add("Key: Not Set");
        }

        return joiner.toString();
    }
}
