package dev.railroadide.railroad.ide.ui;

import dev.railroadide.railroad.ide.console.*;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import lombok.Getter;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ConsolePane extends BorderPane {
    private static final double AUTO_SCROLL_TOLERANCE = 2.0;

    private final InlineCssTextArea outputArea = new InlineCssTextArea();
    private final HBox inputBar = new HBox();
    private final TextField inputField = new TextField();
    @Getter
    private final ConsoleService consoleService = ConsoleService.getInstance();

    private final List<Integer> lineStarts = new ArrayList<>();
    private final List<Integer> lineLengths = new ArrayList<>();

    private boolean autoScroll = true;
    private boolean adjustingScroll = false;

    public ConsolePane() {
        getStyleClass().add("console-pane");

        outputArea.getStyleClass().add("console-output");
        outputArea.setEditable(false);
        outputArea.setWrapText(false);

        VirtualizedScrollPane<InlineCssTextArea> scrollPane = new VirtualizedScrollPane<>(outputArea);
        setCenter(scrollPane);

        inputField.getStyleClass().add("console-input-field");
        inputField.setPromptText("Send input...");
        inputField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                submitInput();
                event.consume();
            }
        });

        inputBar.getStyleClass().add("console-input-bar");
        inputBar.getChildren().add(inputField);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        setInputVisible(false);

        setBottom(inputBar);

        consoleService.addListener(this::onConsoleDelta);
        consoleService.stdinConsumerProperty().addListener((obs, oldValue, newValue) ->
            setInputVisible(newValue != null)
        );

        outputArea.estimatedScrollYProperty().addListener((obs, oldValue, newValue) -> {
            if (!adjustingScroll) {
                autoScroll = isAtBottom();
            }
        });

        rebuildAll(consoleService.createLinesSnapshot());
    }

    private void submitInput() {
        String input = inputField.getText();
        if (input == null || input.isBlank())
            return;

        consoleService.submitStdin(input + System.lineSeparator());
        inputField.clear();
    }

    private void setInputVisible(boolean visible) {
        inputBar.setVisible(visible);
        inputBar.setManaged(visible);
    }

    private void onConsoleDelta(ConsoleDelta delta) {
        if (delta instanceof ConsoleDelta.None)
            return;

        Platform.runLater(() -> applyDelta(delta));
    }

    private void applyDelta(ConsoleDelta delta) {
        if (delta instanceof ConsoleDelta.ResetAll || delta instanceof ConsoleDelta.Cleared) {
            rebuildAll(consoleService.createLinesSnapshot());
        } else if (delta instanceof ConsoleDelta.LinesChanged(int fromLine, int toLine)) {
            List<ConsoleLine> snapshot = consoleService.createLinesSnapshot();
            if (snapshot.isEmpty()) {
                rebuildAll(snapshot);
                return;
            }

            int from = Math.max(0, fromLine);
            int to = Math.min(toLine, snapshot.size() - 1);

            if (lineLengths.size() > snapshot.size()) {
                rebuildAll(snapshot);
                return;
            }

            for (int lineIndex = from; lineIndex <= to; lineIndex++) {
                if (lineIndex < lineLengths.size()) {
                    updateLine(lineIndex, snapshot.get(lineIndex));
                } else {
                    appendLine(snapshot.get(lineIndex));
                }
            }

            if (lineLengths.size() < snapshot.size()) {
                for (int lineIndex = lineLengths.size(); lineIndex < snapshot.size(); lineIndex++) {
                    appendLine(snapshot.get(lineIndex));
                }
            }
        }

        if (autoScroll) {
            scrollToBottom();
        }
    }

    private void rebuildAll(List<ConsoleLine> snapshot) {
        outputArea.replaceText("");
        lineStarts.clear();
        lineLengths.clear();

        var textBuilder = new StringBuilder();
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();

        int offset = 0;
        for (int i = 0; i < snapshot.size(); i++) {
            ConsoleLine line = snapshot.get(i);
            String text = line.getContent();

            lineStarts.add(offset);
            lineLengths.add(text.length());
            textBuilder.append(text);
            StyleSpans<String> lineSpans = buildStyleSpans(line);
            if (lineSpans.length() > 0) {
                spansBuilder.addAll(lineSpans);
            }

            offset += text.length();
            if (i < snapshot.size() - 1) {
                textBuilder.append('\n');
                spansBuilder.add("", 1);
                offset += 1;
            }
        }

        outputArea.replaceText(textBuilder.toString());
        if (!textBuilder.isEmpty()) {
            outputArea.setStyleSpans(0, spansBuilder.create());
        }

        autoScroll = true;
        scrollToBottom();
    }

    private void updateLine(int lineIndex, ConsoleLine line) {
        String text = line.getContent();
        int oldLength = lineLengths.get(lineIndex);
        int newLength = text.length();
        int start = lineStarts.get(lineIndex);
        int end = start + oldLength;

        outputArea.replaceText(start, end, text);
        StyleSpans<String> spans = buildStyleSpans(line);
        if (spans.length() > 0) {
            outputArea.setStyleSpans(start, spans);
        }

        int delta = newLength - oldLength;
        if (delta != 0) {
            lineLengths.set(lineIndex, newLength);
            for (int i = lineIndex + 1; i < lineStarts.size(); i++) {
                lineStarts.set(i, lineStarts.get(i) + delta);
            }
        }
    }

    private void appendLine(ConsoleLine line) {
        boolean needsNewline = !lineLengths.isEmpty();
        if (needsNewline) {
            outputArea.appendText("\n");
        }

        int start = outputArea.getLength();
        String text = line.getContent();
        outputArea.appendText(text);
        StyleSpans<String> spans = buildStyleSpans(line);
        if (spans.length() > 0) {
            outputArea.setStyleSpans(start, spans);
        }

        lineStarts.add(start);
        lineLengths.add(text.length());
    }

    private void scrollToBottom() {
        if (outputArea.getParagraphs().isEmpty())
            return;

        adjustingScroll = true;
        int lastParagraph = outputArea.getParagraphs().size() - 1;
        outputArea.showParagraphAtBottom(lastParagraph);
        Platform.runLater(() -> adjustingScroll = false);
    }

    private boolean isAtBottom() {
        double scrollY = outputArea.estimatedScrollYProperty().getValue();
        double viewport = outputArea.getViewportHeight();
        double totalHeight = outputArea.totalHeightEstimateProperty().getValue();
        return scrollY + viewport >= totalHeight - AUTO_SCROLL_TOLERANCE;
    }

    private StyleSpans<String> buildStyleSpans(ConsoleLine line) {
        int length = line.length();
        if (length == 0)
            return StyleSpans.singleton("", 0);

        List<Span> spans = line.getSpans();
        StyleSpansBuilder<String> builder = new StyleSpansBuilder<>(spans.size() + 1);

        int position = 0;
        for (Span span : spans) {
            if (span.start() > position) {
                builder.add("", span.start() - position);
            }

            builder.add(styleToCss(span.style()), span.end() - span.start());
            position = span.end();
        }

        if (position < length) {
            builder.add("", length - position);
        }

        return builder.create();
    }

    private String styleToCss(Span.SpanStyle style) {
        if (style == null)
            return "";

        var css = new StringBuilder();
        if (style.bold()) {
            css.append("-fx-font-weight: bold;");
        }

        if (style.italic()) {
            css.append("-fx-font-style: italic;");
        }

        if (style.underline()) {
            css.append("-fx-underline: true;");
        }

        if (style.strikethrough()) {
            css.append("-fx-strikethrough: true;");
        }

        String fg = toCssColor(style.fg());
        if (fg != null) {
            css.append("-fx-fill: ").append(fg).append(';');
        }

        String bg = toCssColor(style.bg());
        if (bg != null) {
            css.append("-rtfx-background-color: ").append(bg).append(';');
        }

        return css.toString();
    }

    private String toCssColor(AnsiColor color) {
        switch (color) {
            case null -> {
                return null;
            }
            case AnsiColor.Default ignored -> {
                return null;
            }
            case AnsiColor.Rgb rgb -> {
                return toHex(rgb.r(), rgb.g(), rgb.b());
            }
            case AnsiColor.Indexed indexed -> {
                int[] rgb = ansiIndexToRgb(indexed.index());
                return toHex(rgb[0], rgb[1], rgb[2]);
            }
            default -> {
            }
        }

        return null;
    }

    private String toHex(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    private int[] ansiIndexToRgb(int index) {
        if (index < 16)
            return ansi16(index);

        if (index <= 231) {
            int offset = index - 16;
            int r = offset / 36;
            int g = (offset / 6) % 6;
            int b = offset % 6;
            return new int[]{channelValue(r), channelValue(g), channelValue(b)};
        }

        if (index <= 255) {
            int gray = 8 + (index - 232) * 10;
            return new int[]{gray, gray, gray};
        }

        return new int[]{0, 0, 0};
    }

    private int channelValue(int component) {
        if (component == 0)
            return 0;

        return 55 + component * 40;
    }

    private int[] ansi16(int index) {
        return switch (index) {
            case 1 -> new int[]{205, 49, 49};
            case 2 -> new int[]{13, 188, 121};
            case 3 -> new int[]{229, 229, 16};
            case 4 -> new int[]{36, 114, 200};
            case 5 -> new int[]{188, 63, 188};
            case 6 -> new int[]{17, 168, 205};
            case 7 -> new int[]{229, 229, 229};
            case 8 -> new int[]{102, 102, 102};
            case 9 -> new int[]{241, 76, 76};
            case 10 -> new int[]{35, 209, 139};
            case 11 -> new int[]{245, 245, 67};
            case 12 -> new int[]{59, 142, 234};
            case 13 -> new int[]{214, 112, 214};
            case 14 -> new int[]{41, 184, 219};
            case 15 -> new int[]{255, 255, 255};
            default -> new int[]{0, 0, 0};
        };
    }
}
