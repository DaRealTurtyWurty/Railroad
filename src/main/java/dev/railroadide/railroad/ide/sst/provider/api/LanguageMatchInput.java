package dev.railroadide.railroad.ide.sst.provider.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Immutable, bounded recognition input. Matchers never need to open arbitrary files. */
public final class LanguageMatchInput {
    public static final int MAX_PREFIX_BYTES = 8192;
    private final Path path;
    private final byte[] prefix;

    public LanguageMatchInput(Path path, byte[] prefix) {
        this.path = Objects.requireNonNull(path, "path");
        Objects.requireNonNull(prefix, "prefix");
        this.prefix = Arrays.copyOf(prefix, Math.min(prefix.length, MAX_PREFIX_BYTES));
    }

    public Path path() {
        return path;
    }

    public String fileName() {
        return path.getFileName().toString();
    }

    public byte[] prefix() {
        return Arrays.copyOf(prefix, prefix.length);
    }

    public String textPrefix() {
        return new String(prefix, StandardCharsets.UTF_8);
    }
}
