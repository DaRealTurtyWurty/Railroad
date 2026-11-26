package dev.railroadide.railroad.ide.ui.codeeditor;

/**
 * Language-specific behaviour of an open code editor, supplied by a language session. Covers what the editor's
 * request-based providers cannot: decorations such as gutter columns and folding, and typing rules for indentation,
 * completion and signature help. Every method has a language-neutral default.
 * <p>
 * Methods are called on the JavaFX application thread. Once the providing session closes, editors stop calling
 * these methods and use the defaults.
 */
public interface EditorFeatures {
    /**
     * Features with only the default behaviour.
     */
    EditorFeatures DEFAULT = new EditorFeatures() {
    };

    /**
     * Adds decorations and listeners to a newly opened editor. The editor closes the returned handle once, on the
     * JavaFX application thread, when it closes.
     *
     * @param editor fully constructed editor whose text has been loaded
     * @return handle that removes everything this call added
     */
    default AutoCloseable install(CodeEditorPane editor) {
        return () -> {
        };
    }

    /**
     * Decides whether an opening bracket before an insertion point starts an indented block.
     *
     * @param text current editor text
     * @param bracketOffset offset of the opening bracket in {@code text}
     * @return whether Enter after the bracket should add an indentation level
     */
    default boolean isIndentingBracket(String text, int bracketOffset) {
        return false;
    }

    /**
     * Finds an earlier line to use for the indentation of a new line.
     *
     * @param text current editor text
     * @param offset insertion offset in {@code text}
     * @return an offset on the line to copy indentation from, or {@code -1} to use the insertion line
     */
    default int newlineIndentationAnchor(String text, int offset) {
        return -1;
    }

    /**
     * Decides whether the caret position warrants a signature help request.
     *
     * @param text current editor text
     * @param caret caret offset in {@code text}
     * @param textChanged whether the request follows an edit rather than a caret move
     * @param helpShown whether signature help is currently displayed
     * @return whether signature help should be requested; by default only after edits
     */
    default boolean shouldRequestSignatureHelp(String text, int caret, boolean textChanged, boolean helpShown) {
        return textChanged;
    }

    /**
     * Decides whether a code point continues an identifier, which bounds the text replaced by completion.
     *
     * @param codePoint code point to test
     * @return whether the code point can be part of an identifier; by default the Java rule
     */
    default boolean isIdentifierPart(int codePoint) {
        return Character.isJavaIdentifierPart(codePoint);
    }
}
