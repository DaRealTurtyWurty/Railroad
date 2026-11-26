package dev.railroadide.railroad.ide.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable model representing a single completion entry.
 *
 * @param insertText text used to match the prefix and replace it, before any call parentheses
 * @param label main text shown in the completion list
 * @param suffix signature text following the label, such as method parameters
 * @param detail supplementary text shown at the trailing edge, such as a return type
 * @param kind symbol kind used to identify the suggestion visually
 * @param hasParameters whether the selected method declares any parameters
 * @param template declaration inserted in place of {@link #insertText()}, or {@code null} to insert the name
 */
public record CompletionItem(
    String insertText,
    String label,
    String suffix,
    String detail,
    Kind kind,
    boolean hasParameters,
    @Nullable Template template
) {
    /**
     * Symbol categories displayed by the completion popup.
     */
    public enum Kind {
        /**
         * Plain text supplied by a provider.
         */
        TEXT,
        /**
         * A method or constructor.
         */
        METHOD,
        /**
         * A field or variable.
         */
        FIELD,
        /**
         * A class, interface, enum or record.
         */
        TYPE,
        /**
         * An inherited method inserted as an overriding declaration.
         */
        OVERRIDE
    }

    /**
     * Multi-line text inserted when a suggestion is accepted.
     * <p>
     * Lines after the first are indented to the line on which the replacement starts. Each leading tab of a line
     * denotes one indentation level and is rendered with the editor's configured indentation.
     *
     * @param text template text using LF line separators
     * @param caretOffset offset within {@code text} at which the caret is placed after insertion
     * @param insertions further text inserted before the replacement, such as imports, in ascending offset order
     */
    public record Template(String text, int caretOffset, List<Insertion> insertions) {
        /**
         * Validates the caret offset and the order of the additional insertions.
         *
         * @param text template text using LF line separators
         * @param caretOffset offset within {@code text} at which the caret is placed after insertion
         * @param insertions further text inserted before the replacement, in ascending offset order
         * @throws IllegalArgumentException if the caret lies outside the text or insertions share or reverse offsets
         */
        public Template {
            if (caretOffset < 0 || caretOffset > text.length())
                throw new IllegalArgumentException("Template caret offset is outside the text");

            insertions = List.copyOf(insertions);
            for (int index = 1; index < insertions.size(); index++) {
                if (insertions.get(index).offset() <= insertions.get(index - 1).offset())
                    throw new IllegalArgumentException("Template insertions must have ascending offsets");
            }
        }

        /**
         * Creates a template without additional insertions.
         *
         * @param text template text using LF line separators
         * @param caretOffset offset within {@code text} at which the caret is placed after insertion
         */
        public Template(String text, int caretOffset) {
            this(text, caretOffset, List.of());
        }
    }

    /**
     * Text inserted verbatim at an offset of the document the suggestion was computed for.
     *
     * @param offset UTF-16 offset in the requested document, at or before the suggestion's replacement start
     * @param text inserted text
     */
    public record Insertion(int offset, String text) {
        /**
         * Validates the offset.
         *
         * @param offset UTF-16 offset in the requested document
         * @param text inserted text
         * @throws IllegalArgumentException if the offset is negative
         */
        public Insertion {
            if (offset < 0)
                throw new IllegalArgumentException("Insertion offset must not be negative");
        }
    }

    /**
     * Creates a plain suggestion preserving the provider's complete display text.
     *
     * @param insertText text to insert
     * @param displayText provider-supplied label
     */
    public CompletionItem(String insertText, String displayText) {
        this(insertText, displayText, "", "", Kind.TEXT, false, null);
    }

    /**
     * Creates a method suggestion.
     *
     * @param name method name to insert
     * @param parameters formatted parameter list without parentheses
     * @param returnType return type displayed beside the signature
     * @param isStatic whether the method is static
     * @return a method suggestion
     */
    public static CompletionItem method(String name, String parameters, String returnType, boolean isStatic) {
        return new CompletionItem(name, label(name, isStatic), "(" + parameters + ")", returnType, Kind.METHOD,
            !parameters.isBlank(), null);
    }

    /**
     * Creates a field suggestion.
     *
     * @param name field name to insert
     * @param type declared field type
     * @param isStatic whether the field is static
     * @return a field suggestion
     */
    public static CompletionItem field(String name, String type, boolean isStatic) {
        return new CompletionItem(name, label(name, isStatic), "", type, Kind.FIELD, false, null);
    }

    /**
     * Creates a type suggestion.
     *
     * @param name type name to insert
     * @param isStatic whether the type is static
     * @return a type suggestion
     */
    public static CompletionItem type(String name, boolean isStatic) {
        return new CompletionItem(name, label(name, isStatic), "", "", Kind.TYPE, false, null);
    }

    /**
     * Creates a suggestion that inserts an overriding declaration of an inherited method.
     *
     * @param name overridden method name, matched against the typed prefix
     * @param parameters formatted parameter list without parentheses
     * @param declaringType simple name of the type declaring the overridden method
     * @param template declaration inserted when the suggestion is accepted
     * @return an override suggestion
     */
    public static CompletionItem override(String name, String parameters, String declaringType, Template template) {
        return new CompletionItem(name, name, "(" + parameters + ")", declaringType, Kind.OVERRIDE,
            !parameters.isBlank(), template);
    }

    private static String label(String name, boolean isStatic) {
        return isStatic ? "static " + name : name;
    }

    /**
     * Returns the full label of this suggestion.
     *
     * @return complete label for accessibility, tooltips and plain-text consumers
     */
    public String displayText() {
        if (kind == Kind.TYPE)
            return label + " (type)";

        return label + suffix + (detail.isEmpty() ? "" : " : " + detail);
    }

    @Override
    public @NotNull String toString() {
        return displayText();
    }
}
