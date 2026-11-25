package dev.railroadide.railroad.ide.syntaxhighlighting;

import dev.railroadide.railroad.ide.WorkspaceModes;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.language.LanguageSupports;
import dev.railroadide.railroad.ide.language.impl.GroovyLanguageSupport;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class GroovySyntaxHighlightingTest {
    @BeforeAll
    public static void initialize() {
        WorkspaceModes.initialize();
        LanguageSupports.initialize();
    }

    @Test
    public void stylesKeywordsNumbersAndComments() {
        String text = "def total = 42 // running total";
        StyleSpans<Collection<String>> spans = GroovySyntaxHighlighting.computeHighlighting(text);

        assertStyle(spans, text.indexOf("def"), "keyword");
        assertStyle(spans, text.indexOf("total"), null);
        assertStyle(spans, text.indexOf('='), "operator");
        assertStyle(spans, text.indexOf("42"), "number");
        assertStyle(spans, text.indexOf("//"), "comment");
        assertStyle(spans, text.length() - 1, "comment");
    }

    @Test
    public void stylesAnnotationsTypesAndGStrings() {
        String text = "@Override\nString greet(String name) { \"Hello ${name}\" }\n";
        StyleSpans<Collection<String>> spans = GroovySyntaxHighlighting.computeHighlighting(text);

        assertStyle(spans, 0, "annotation");
        assertStyle(spans, text.indexOf("Override"), "annotation");
        assertStyle(spans, text.indexOf("String"), "type");
        assertStyle(spans, text.indexOf('('), "punctuation");
        assertStyle(spans, text.indexOf("\"Hello"), "string");
        assertStyle(spans, text.indexOf("name}"), null);
    }

    @Test
    public void stylesSlashyStringsAsStrings() {
        String text = "def pattern = /a+b/\n";
        StyleSpans<Collection<String>> spans = GroovySyntaxHighlighting.computeHighlighting(text);

        assertStyle(spans, text.indexOf("/a"), "string");
        assertStyle(spans, text.indexOf("b/"), "string");
    }

    @Test
    public void resumesOnTheNextLineAfterALexicalError() {
        String text = "def broken = \"unterminated\nclass Fixed {}\n";
        StyleSpans<Collection<String>> spans = GroovySyntaxHighlighting.computeHighlighting(text);

        assertStyle(spans, 0, "keyword");
        assertStyle(spans, text.indexOf("class"), "keyword");
        assertStyle(spans, text.indexOf("Fixed"), "type");
    }

    @Test
    public void mapsOffsetsPastSupplementaryCharacters() {
        String text = "def face = '😀'; def after = 1";
        StyleSpans<Collection<String>> spans = GroovySyntaxHighlighting.computeHighlighting(text);

        assertStyle(spans, text.indexOf('\''), "string");
        assertStyle(spans, text.lastIndexOf("def"), "keyword");
        assertStyle(spans, text.indexOf("after"), null);
        assertStyle(spans, text.length() - 1, "number");
    }

    @Test
    public void spansCoverTheWholeText() {
        for (String text : List.of("", "def", "\"open", "x = 1\n\n// end", "/* never closed")) {
            assertEquals(text.length(), GroovySyntaxHighlighting.computeHighlighting(text).length(), text);
        }
    }

    @Test
    public void groovyFilesOpenAsGroovy() {
        assertEquals(GroovyLanguageSupport.LANGUAGE_ID,
            LanguageSupportRegistry.resolveLanguageId(Path.of("Script.groovy"), new byte[0]));
        assertEquals("Groovy", LanguageSupportRegistry.find(Path.of("src/main/groovy/App.groovy"))
            .orElseThrow().displayName());
    }

    private static void assertStyle(StyleSpans<Collection<String>> spans, int offset, String expected) {
        assertTrue(offset >= 0, "Offset must exist in the text");
        int position = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            if (offset < position + span.getLength()) {
                assertEquals(expected == null ? Set.of() : Set.of(expected), Set.copyOf(span.getStyle()),
                    "Style at offset " + offset);
                return;
            }

            position += span.getLength();
        }

        fail("Offset " + offset + " is past the highlighted text");
    }
}
