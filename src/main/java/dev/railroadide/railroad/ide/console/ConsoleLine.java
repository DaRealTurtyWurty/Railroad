package dev.railroadide.railroad.ide.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ConsoleLine {
    private final StringBuilder content = new StringBuilder();

    private final List<Span> spans = new ArrayList<>();

    public String getContent() {
        return content.toString();
    }

    public List<Span> getSpans() {
        return List.copyOf(this.spans);
    }

    public void overwriteCharAt(int column, char c, Span.SpanStyle style) {
        if (column < 0)
            return;

        if (column > content.length()) {
            int pad = column - content.length();
            appendText(" ".repeat(pad), Span.SpanStyle.DEFAULT);
        }

        if (column == content.length()) {
            appendChar(c, style);
            return;
        } else {
            content.setCharAt(column, c);
        }

        setStyleAt(column, style);
    }

    public void appendChar(char c, Span.SpanStyle style) {
        int startIndex = content.length();
        content.append(c);

        // We don't need to store the span for default style
        if (style == null || Objects.equals(style, Span.SpanStyle.DEFAULT))
            return;

        int end = content.length();
        addSpanRange(startIndex, end, style);
    }

    public void appendText(CharSequence text, Span.SpanStyle style) {
        if (text == null || text.isEmpty())
            return;

        int startIndex = content.length();
        content.append(text);

        // We don't need to store the span for default style
        if (style == null || Objects.equals(style, Span.SpanStyle.DEFAULT))
            return;

        int end = content.length();
        addSpanRange(startIndex, end, style);
    }

    public int length() {
        return content.length();
    }

    public void truncate(int newLength) {
        int length = content.length();
        if (newLength >= length)
            return;
        if (newLength <= 0) {
            content.setLength(0);
            spans.clear();
            return;
        }

        content.setLength(newLength);
        for (int i = spans.size() - 1; i >= 0; i--) {
            Span span = spans.get(i);
            if (span.start() >= newLength) {
                spans.remove(i);
            } else if (span.end() > newLength) {
                spans.set(i, new Span(span.start(), newLength, span.style()));
            }
        }
    }

    private void addSpanRange(int startIndex, int endIndex, Span.SpanStyle style) {
        if (startIndex >= endIndex)
            return;

        if (spans.isEmpty()) {
            spans.add(new Span(startIndex, endIndex, style));
            return;
        }

        Span lastSpan = spans.getLast();
        if (lastSpan.style().equals(style) && lastSpan.end() == startIndex) {
            spans.set(spans.size() - 1, lastSpan.extendTo(endIndex));
        } else {
            spans.add(new Span(startIndex, endIndex, style));
        }
    }

    private void setStyleAt(int index, Span.SpanStyle style) {
        int spanIndex = findSpanIndexContaining(index);
        if (spanIndex == -1) {
            // No existing span contains this index, so we need to create a new one
            insertSpanAtCorrectPosition(index, index + 1, style);
            coalesceAround(findSpanIndexContaining(index));
            return;
        }

        Span span = spans.get(spanIndex);
        if (span.style().equals(style))
            return; // Style is already correct

        int start = span.start();
        int end = span.end();

        // Replace the span with up to 3 spans: left, middle, right
        spans.remove(spanIndex);

        // Right span
        if (index + 1 < end) {
            spans.add(spanIndex, new Span(index + 1, end, span.style()));
        }

        // Middle span (overwritten character)
        spans.add(spanIndex, new Span(index, index + 1, style));

        // Left span
        if (start < index) {
            spans.add(spanIndex, new Span(start, index, span.style()));
        }

        // Clean up the merges if neighbours now have the same style
        coalesceAround(spanIndex);
    }

    private int findSpanIndexContaining(int index) {
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            if (span.start() <= index && index < span.end())
                return i;
        }

        return -1;
    }

    private void insertSpanAtCorrectPosition(int startIndex, int endIndex, Span.SpanStyle style) {
        int insertPos = 0;
        while (insertPos < spans.size() && spans.get(insertPos).start() < startIndex) {
            insertPos++;
        }

        spans.add(insertPos, new Span(startIndex, endIndex, style));
    }

    private void coalesceAround(int index) {
        if(spans.isEmpty())
            return;

        index = Math.clamp(index, 0, spans.size() - 1);

        // Coalesce with previous span
        if(index > 0) {
            Span previousSpan = spans.get(index - 1);
            Span currentSpan = spans.get(index);
            if(previousSpan.style().equals(currentSpan.style()) && previousSpan.end() == currentSpan.start()) {
                spans.set(index - 1, new Span(previousSpan.start(), currentSpan.end(), previousSpan.style()));
                spans.remove(index);
                index--;
            }
        }

        // Coalesce with next span
        if(index < spans.size() - 1) {
            Span currentSpan = spans.get(index);
            Span nextSpan = spans.get(index + 1);
            if (currentSpan.style().equals(nextSpan.style()) && currentSpan.end() == nextSpan.start()) {
                spans.set(index, new Span(currentSpan.start(), nextSpan.end(), currentSpan.style()));
                spans.remove(index + 1);
            }
        }
    }
}
