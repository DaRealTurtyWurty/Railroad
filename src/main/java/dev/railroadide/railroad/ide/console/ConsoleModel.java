package dev.railroadide.railroad.ide.console;

import dev.railroadide.railroad.settings.Settings;

import java.util.ArrayList;
import java.util.List;

public final class ConsoleModel {
    private final int maxLines;

    private final List<ConsoleLine> lines = new ArrayList<>();

    private final AnsiParser ansi = new AnsiParser();

    private int cursorLine;
    private int cursorColumn;

    private boolean lineIsLive;

    private Span.SpanStyle currentStyle;
    private ConsoleStream currentStream;
    private int savedCursorLine;
    private int savedCursorColumn;
    private boolean hasSavedCursor;

    public ConsoleModel(int maxLines) {
        this.maxLines = maxLines;
        lines.add(new ConsoleLine());
        currentStream = ConsoleStream.STDOUT;
        currentStyle = Span.SpanStyle.ofStream(currentStream);
    }

    public List<ConsoleLine> createLinesSnapshot() {
        return new ArrayList<>(lines);
    }

    public ConsoleDelta write(CharSequence chunk, ConsoleStream stream) {
        if (chunk == null || chunk.isEmpty())
            return new ConsoleDelta.None();

        if (stream != currentStream)
            currentStream = stream;

        int startChanged = cursorLine;
        final int[] endChanged = {cursorLine};

        ansi.feed(chunk, new AnsiParser.Sink() {
            @Override
            public void print(char c) {
                writeChar(c, currentStyle);
                endChanged[0] = Math.max(endChanged[0], cursorLine);
            }

            @Override
            public void control(AnsiParser.Control control) {
                switch (control) {
                    case LF -> newline();
                    case CR -> carriageReturn();
                    case BS -> backspace();
                    case TAB -> tab();
                    case SAVE_CURSOR -> saveCursor();
                    case RESTORE_CURSOR -> restoreCursor();
                }

                endChanged[0] = Math.max(endChanged[0], cursorLine);
            }

            @Override
            public void csi(AnsiParser.CsiCommand command) {
                handleCsi(command);
                endChanged[0] = Math.max(endChanged[0], cursorLine);
            }
        });

        if (trimToMaxLinesIfNeeded())
            return new ConsoleDelta.ResetAll();

        return new ConsoleDelta.LinesChanged(startChanged, endChanged[0]);
    }

    public void clear() {
        lines.clear();
        lines.add(new ConsoleLine());
        cursorLine = 0;
        cursorColumn = 0;
        lineIsLive = false;
    }

    private ConsoleLine currentLine() {
        while (cursorLine >= lines.size()) {
            lines.add(new ConsoleLine());
        }

        return lines.get(cursorLine);
    }

    private void writeChar(char c, Span.SpanStyle style) {
        ConsoleLine line = currentLine();
        if (cursorColumn < line.length()) {
            line.overwriteCharAt(cursorColumn, c, style);
        } else {
            if (cursorColumn == line.length()) {
                line.appendChar(c, style);
            } else {
                line.overwriteCharAt(cursorColumn, c, style); // pads for us
            }
        }

        cursorColumn++;
        lineIsLive = true;
    }

    private void writeRepeatedChar(char c, int count, Span.SpanStyle style) {
        for (int i = 0; i < count; i++) {
            writeChar(c, style);
        }
    }

    private void newline() {
        cursorLine++;
        cursorColumn = 0;
        lineIsLive = false;

        if (cursorLine >= lines.size()) {
            lines.add(new ConsoleLine());
        }
    }

    private void carriageReturn() {
        cursorColumn = 0;
        lineIsLive = true;
    }

    private void backspace() {
        if (cursorColumn > 0) {
            cursorColumn--;
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private void tab() {
        if (Settings.CONSOLE_USE_SPACES_FOR_TABS.getValue()) {
            int tabWidth = Settings.CONSOLE_SPACES_PER_TAB.getValue();
            int nextStop = ((cursorColumn / tabWidth) + 1) * tabWidth;
            int spacesToInsert = nextStop - cursorColumn;
            writeRepeatedChar(' ', spacesToInsert, currentStyle);
        } else {
            writeChar('\t', currentStyle);
        }
    }

    private void handleCsi(AnsiParser.CsiCommand cmd) {
        int[] p = cmd.params();
        char f = cmd.finalChar();

        switch (f) {
            case 'm' -> applySgr(p);
            case 'K' -> eraseInLine(firstOr(p, 0));       // 0,1,2
            case 'J' -> eraseInDisplay(firstOr(p, 0));    // 0,1,2
            case 'A' -> cursorUp(countOrOne(p));
            case 'B' -> cursorDown(countOrOne(p));
            case 'C' -> cursorForward(countOrOne(p));
            case 'D' -> cursorBack(countOrOne(p));
            case 'G' -> cursorToColumn(firstOr(p, 1));    // 1-based
            case 'H', 'f' -> cursorTo(firstOr(p, 1), (p.length > 1 ? p[1] : 1)); // row;col (1-based)
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            default -> {
                // ignore for now
            }
        }
    }

    private void applySgr(int[] params) {
        if (params.length == 0) params = new int[]{0};

        for (int i = 0; i < params.length; i++) {
            int code = params[i];
            switch (code) {
                case 0 -> currentStyle = Span.SpanStyle.ofStream(currentStream); // reset
                case 1 -> currentStyle = currentStyle.withBold(true);
                case 22 -> currentStyle = currentStyle.withBold(false);
                case 3 -> currentStyle = currentStyle.withItalic(true);
                case 23 -> currentStyle = currentStyle.withItalic(false);
                case 4 -> currentStyle = currentStyle.withUnderline(true);
                case 24 -> currentStyle = currentStyle.withUnderline(false);
                case 9 -> currentStyle = currentStyle.withStrikethrough(true);
                case 29 -> currentStyle = currentStyle.withStrikethrough(false);
                case 39 -> currentStyle = currentStyle.withFg(AnsiColor.DEFAULT_FG);
                case 49 -> currentStyle = currentStyle.withBg(AnsiColor.DEFAULT_BG);
                case 38 -> i = applyExtendedColor(params, i, true);
                case 48 -> i = applyExtendedColor(params, i, false);
                default -> {
                    if (code >= 30 && code <= 37) {
                        currentStyle = currentStyle.withFg(new AnsiColor.Indexed(code - 30));
                    } else if (code >= 90 && code <= 97) {
                        currentStyle = currentStyle.withFg(new AnsiColor.Indexed(code - 90 + 8));
                    } else if (code >= 40 && code <= 47) {
                        currentStyle = currentStyle.withBg(new AnsiColor.Indexed(code - 40));
                    } else if (code >= 100 && code <= 107) {
                        currentStyle = currentStyle.withBg(new AnsiColor.Indexed(code - 100 + 8));
                    }
                }
            }
        }
    }

    private int applyExtendedColor(int[] params, int index, boolean foreground) {
        if (index + 1 >= params.length)
            return index;

        int mode = params[index + 1];
        if (mode == 5 && index + 2 < params.length) {
            int paletteIndex = params[index + 2];
            if (paletteIndex >= 0 && paletteIndex <= 255) {
                if (foreground) {
                    currentStyle = currentStyle.withFg(new AnsiColor.Indexed(paletteIndex));
                } else {
                    currentStyle = currentStyle.withBg(new AnsiColor.Indexed(paletteIndex));
                }
            }
            return index + 2;
        }

        if (mode == 2 && index + 4 < params.length) {
            int r = params[index + 2];
            int g = params[index + 3];
            int b = params[index + 4];
            if ((r | g | b) >= 0 && r <= 255 && g <= 255 && b <= 255) {
                if (foreground) {
                    currentStyle = currentStyle.withFg(new AnsiColor.Rgb(r, g, b));
                } else {
                    currentStyle = currentStyle.withBg(new AnsiColor.Rgb(r, g, b));
                }
            }
            return index + 4;
        }

        return index;
    }

    private void eraseInLine(int mode) {
        ConsoleLine line = currentLine();

        // ensure cursorColumn isn't beyond end when clearing to end
        int len = line.length();

        switch (mode) {
            case 0 -> { // cursor -> end
                for (int col = cursorColumn; col < len; col++) {
                    line.overwriteCharAt(col, ' ', currentStyle);
                }
            }
            case 1 -> { // start -> cursor
                int to = Math.min(cursorColumn, len);
                for (int col = 0; col < to; col++) {
                    line.overwriteCharAt(col, ' ', currentStyle);
                }
            }
            case 2 -> { // whole line
                for (int col = 0; col < len; col++) {
                    line.overwriteCharAt(col, ' ', currentStyle);
                }

                cursorColumn = 0; // terminals do not necessarily move cursor
            }
            default -> { /* ignore */ }
        }
    }

    private void eraseInDisplay(int mode) {
        switch (mode) {
            case 0 -> {
                currentLine().truncate(cursorColumn);
                if (cursorLine + 1 < lines.size()) {
                    lines.subList(cursorLine + 1, lines.size()).clear();
                }
            }
            case 1 -> {
                for (int i = 0; i < cursorLine && i < lines.size(); i++) {
                    lines.get(i).truncate(0);
                }
                int max = Math.max(cursorColumn, currentLine().length());
                for (int col = 0; col < max; col++) {
                    currentLine().overwriteCharAt(col, ' ', Span.SpanStyle.DEFAULT);
                }
            }
            case 2 -> clear();
            default -> { /* ignore */ }
        }
    }

    private void cursorUp(int n) {
        cursorLine = Math.max(0, cursorLine - n);
    }

    private void cursorDown(int n) {
        cursorLine = Math.min(lines.size() - 1, cursorLine + n);
    }

    private void cursorForward(int n) {
        cursorColumn += n;
    }

    private void cursorBack(int n) {
        cursorColumn = Math.max(0, cursorColumn - n);
    }

    private void cursorToColumn(int oneBasedCol) {
        cursorColumn = Math.max(0, oneBasedCol - 1);
    }

    private void cursorTo(int oneBasedRow, int oneBasedCol) {
        cursorLine = Math.max(0, Math.min(lines.size() - 1, oneBasedRow - 1));
        cursorToColumn(oneBasedCol);
    }

    private void saveCursor() {
        savedCursorLine = cursorLine;
        savedCursorColumn = cursorColumn;
        hasSavedCursor = true;
    }

    private void restoreCursor() {
        if (!hasSavedCursor)
            return;

        cursorLine = Math.max(0, Math.min(lines.size() - 1, savedCursorLine));
        cursorColumn = Math.max(0, savedCursorColumn);
    }

    private static int firstOr(int[] p, int def) {
        return p.length > 0 ? p[0] : def;
    }

    private static int countOrOne(int[] p) {
        int v = (p.length == 0 ? 1 : p[0]);
        return v == 0 ? 1 : v;
    }

    private boolean trimToMaxLinesIfNeeded() {
        if (lines.size() <= maxLines)
            return false;

        int linesToRemove = lines.size() - maxLines;
        if (linesToRemove > 0) {
            lines.subList(0, linesToRemove).clear();
        }

        cursorLine -= linesToRemove;
        if (cursorLine < 0) {
            cursorLine = 0;
            cursorColumn = 0;
        }

        return true;
    }
}
