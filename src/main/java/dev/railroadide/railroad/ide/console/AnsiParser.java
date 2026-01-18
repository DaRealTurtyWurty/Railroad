package dev.railroadide.railroad.ide.console;

public final class AnsiParser {
    public interface Sink {
        void print(char c);

        void control(Control control);

        void csi(CsiCommand command);
    }

    public enum Control {
        LF,
        CR,
        BS,
        TAB,
        SAVE_CURSOR,
        RESTORE_CURSOR
    }

    public record CsiCommand(int[] params, char finalChar) {
    }

    private enum State {
        TEXT,
        ESCAPE,
        CSI
    }

    private State state = State.TEXT;
    private final StringBuilder paramBuffer = new StringBuilder();

    public void reset() {
        state = State.TEXT;
        paramBuffer.setLength(0);
    }

    public void feed(CharSequence input, Sink sink) {
        for (int i = 0; i < input.length(); i++) {
            feed(input.charAt(i), sink);
        }
    }

    public void feed(char c, Sink sink) {
        switch (state) {
            case TEXT -> {
                if (c == 0x1B) { // ESC
                    state = State.ESCAPE;
                } else if (c == '\n') {
                    sink.control(Control.LF);
                } else if (c == '\r') {
                    sink.control(Control.CR);
                } else if (c == '\b') {
                    sink.control(Control.BS);
                } else if (c == '\t') {
                    sink.control(Control.TAB);
                } else {
                    sink.print(c);
                }
            }
            case ESCAPE -> {
                if (c == '[') {
                    state = State.CSI;
                    paramBuffer.setLength(0);
                } else if (c == '7') {
                    state = State.TEXT;
                    sink.control(Control.SAVE_CURSOR);
                } else if (c == '8') {
                    state = State.TEXT;
                    sink.control(Control.RESTORE_CURSOR);
                } else {
                    // Unsupported escape sequence, return to text
                    state = State.TEXT;
                }
            }
            case CSI -> {
                // CSI params are digits, ';', sometimes '?' etc. We’ll ignore '?' for now.
                if ((c >= '0' && c <= '9') || c == ';' || c == '?') {
                    paramBuffer.append(c);
                } else {
                    // final byte of CSI
                    int[] params = parseParams(paramBuffer);
                    sink.csi(new CsiCommand(params, c));
                    state = State.TEXT;
                }
            }
        }
    }

    private int[] parseParams(StringBuilder paramBuffer) {
        if (paramBuffer == null || paramBuffer.isEmpty())
            return new int[0];

        String[] parts = paramBuffer.toString().split(";");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                out[i] = 0;
            } else {
                try {
                    out[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    out[i] = 0;
                }
            }
        }

        return out;
    }
}
