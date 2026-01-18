package dev.railroadide.railroad.ide.console;

public sealed interface AnsiColor permits AnsiColor.Default, AnsiColor.Indexed, AnsiColor.Rgb {
    record Default(boolean foreground) implements AnsiColor {
    }

    record Indexed(int index) implements AnsiColor {
        public Indexed {
            if (index < 0 || index > 255)
                throw new IllegalArgumentException("ANSI color index out of range");
        }
    }

    record Rgb(int r, int g, int b) implements AnsiColor {
        public Rgb {
            if ((r | g | b) < 0 || r > 255 || g > 255 || b > 255)
                throw new IllegalArgumentException("RGB out of range");
        }
    }

    AnsiColor DEFAULT_FG = new Default(true);
    AnsiColor DEFAULT_BG = new Default(false);
}
