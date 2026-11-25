package dev.railroadide.railroad.ide.ui.refactor;

import dev.railroadide.railroad.ide.sst.document.api.TextRange;
import dev.railroadide.railroad.ide.sst.provider.api.RenamePreparation;
import dev.railroadide.railroad.localization.L18n;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyledDocument;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.undo.UndoManagerFactory;
import org.reactfx.EventSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javafx.scene.Node;
import org.fxmisc.richtext.MultiChangeBuilder;

/**
 * An isolated, styled view of the source while a name is being edited. Only linked identifiers accept input.
 * The source editor, its save machinery and its undo manager see only the final workspace edit.
 */
public final class InlineRenameEditor extends CodeArea {
    private final EventSource<PlainTextChange> nameChanges = new EventSource<>();
    private List<TextRange> occurrences;
    private int active;
    private String name;

    /**
     * Selects the prepared occurrence in a private copy of the styled document.
     *
     * @param source open document editor
     * @param symbol prepared symbol and linked ranges
     * @param review invoked with the edited name on Enter
     * @param discard cancels the session on Escape
     */
    public InlineRenameEditor(
        CodeArea source,
        RenamePreparation.Renameable symbol,
        Consumer<String> review,
        Runnable discard
    ) {
        setUndoManager(UndoManagerFactory.unlimitedHistorySingleChangeUM(nameChanges, PlainTextChange::invert,
            change -> update(change.getInserted(), change.getInserted().length())));
        super.replace(0, 0, source.subDocument(0, source.getLength()));
        getStyleClass().setAll(source.getStyleClass());
        getStyleClass().add("inline-rename-editor");
        setStyle(source.getStyle());
        setWrapText(source.isWrapText());
        IntFunction<? extends Node> gutter = source.getParagraphGraphicFactory();
        if (gutter != null) {
            setParagraphGraphicFactory(line -> {
                Node graphic = gutter.apply(line);
                if (graphic != null) {
                    graphic.setMouseTransparent(true);
                }

                return graphic;
            });
        }

        setAccessibleHelp(L18n.localize("railroad.rename.inline.help"));
        occurrences = symbol.occurrences();
        active = occurrences.indexOf(symbol.range().range());
        name = symbol.name();
        highlight();
        selectAll();
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                review.accept(name);
            } else if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                discard.run();
            }
        });
    }

    @Override
    public void replace(
        int start,
        int end,
        StyledDocument<Collection<String>, String, Collection<String>> replacement
    ) {
        String inserted = replacement.getText();
        if (inserted.indexOf('\n') >= 0 || inserted.indexOf('\r') >= 0)
            return;

        for (int index = 0; index < occurrences.size(); index++) {
            TextRange range = occurrences.get(index);
            if (start < range.start || end > range.end)
                continue;

            active = index;
            String edited = getText(range.start, start) + inserted + getText(end, range.end);
            if (!edited.equals(name)) {
                update(edited, start - range.start + inserted.length());
            }

            return;
        }
    }

    @Override
    public void selectAll() {
        TextRange range = occurrences.get(active);
        selectRange(range.start, range.end);
    }

    private void update(String edited, int caret) {
        MultiChangeBuilder<Collection<String>, String, Collection<String>> changes = createMultiChange(
            occurrences.size());
        List<TextRange> updated = new ArrayList<>(occurrences.size());
        int shift = 0;
        for (TextRange range : occurrences) {
            changes.replaceText(range.start, range.end, edited);
            int start = range.start + shift;
            updated.add(new TextRange(start, start + edited.length()));
            shift += edited.length() - range.length();
        }

        changes.commit();
        var change = new PlainTextChange(0, name, edited);
        name = edited;
        occurrences = updated;
        highlight();
        moveTo(occurrences.get(active).start + caret);
        requestFollowCaret();
        nameChanges.push(change);
    }

    private void highlight() {
        for (TextRange range : occurrences) {
            if (range.length() > 0) {
                setStyleClass(range.start, range.end, "inline-rename-occurrence");
            }
        }
    }
}
