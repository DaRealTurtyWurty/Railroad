package dev.railroadide.railroad.ide.console;

public record Span(int start, int end, SpanStyle style) {
    public Span {
        if (start < 0 || end < start)
            throw new IllegalArgumentException("Invalid span range: [" + start + ", " + end + "]");
    }

    public Span extendTo(int newEnd) {
        if (newEnd < end)
            throw new IllegalArgumentException("New end must be greater than or equal to current end.");
        return new Span(start, newEnd, style);
    }

    public record SpanStyle(ConsoleStream stream, boolean bold, boolean italic, boolean underline,
                            boolean strikethrough, AnsiColor fg, AnsiColor bg) {
        public static final SpanStyle DEFAULT = new SpanStyle(ConsoleStream.STDOUT, false, false, false, false, AnsiColor.DEFAULT_FG, AnsiColor.DEFAULT_BG);

        public static SpanStyle ofStream(ConsoleStream stream) {
            return switch (stream) {
                case STDOUT ->
                    new SpanStyle(ConsoleStream.STDOUT, false, false, false, false, AnsiColor.DEFAULT_FG, AnsiColor.DEFAULT_BG);
                case STDERR ->
                    new SpanStyle(ConsoleStream.STDERR, false, false, false, false, AnsiColor.DEFAULT_FG, AnsiColor.DEFAULT_BG);
                case SYSTEM ->
                    new SpanStyle(ConsoleStream.SYSTEM, true, false, false, false, AnsiColor.DEFAULT_FG, AnsiColor.DEFAULT_BG);
            };
        }

        public SpanStyle withBold(boolean bold) {
            return new SpanStyle(stream, bold, italic, underline, strikethrough, fg, bg);
        }

        public SpanStyle withItalic(boolean italic) {
            return new SpanStyle(stream, bold, italic, underline, strikethrough, fg, bg);
        }

        public SpanStyle withUnderline(boolean underline) {
            return new SpanStyle(stream, bold, italic, underline, strikethrough, fg, bg);
        }

        public SpanStyle withStrikethrough(boolean strikethrough) {
            return new SpanStyle(stream, bold, italic, underline, strikethrough, fg, bg);
        }

        public SpanStyle withFg(AnsiColor fg) {
            return new SpanStyle(stream, bold, italic, underline, strikethrough, fg, bg);
        }

        public SpanStyle withBg(AnsiColor bg) {
            return new SpanStyle(stream, bold, italic, underline, strikethrough, fg, bg);
        }
    }
}
