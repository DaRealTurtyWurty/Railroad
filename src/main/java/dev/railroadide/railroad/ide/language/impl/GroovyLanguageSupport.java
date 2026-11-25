package dev.railroadide.railroad.ide.language.impl;

import dev.railroadide.railroad.ide.diagnostics.GroovyDiagnosticsProvider;
import dev.railroadide.railroad.ide.language.BaseLanguageSupport;
import dev.railroadide.railroad.ide.language.EditorOpenView;
import dev.railroadide.railroad.ide.language.LanguageCapabilities;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.provider.api.CapabilityKey;
import dev.railroadide.railroad.ide.sst.provider.api.CapabilityRegistry;
import dev.railroadide.railroad.ide.sst.provider.api.LanguageSession;
import dev.railroadide.railroad.ide.sst.provider.api.LanguageSessionContext;
import dev.railroadide.railroad.ide.sst.provider.api.SstCapabilities;
import dev.railroadide.railroad.ide.syntaxhighlighting.GroovySyntaxHighlighting;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorConfig;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorPane;
import dev.railroadide.railroad.plugin.spi.dto.Project;

import java.nio.file.Path;
import java.util.Set;

/**
 * Edits Groovy source files with syntax highlighting from the Groovy lexer and diagnostics from the Groovy compiler.
 */
public final class GroovyLanguageSupport extends BaseLanguageSupport {
    /**
     * Stable identifier for Groovy language features.
     */
    public static final String LANGUAGE_ID = "groovy";

    /**
     * Creates support for Groovy source files.
     */
    public GroovyLanguageSupport() {
        super(LANGUAGE_ID, "Groovy", Set.of("groovy"));
    }

    @Override
    public Set<CapabilityKey<?>> supportedCapabilities() {
        return Set.of(LanguageCapabilities.HIGHLIGHTING, SstCapabilities.DIAGNOSTICS);
    }

    @Override
    public LanguageSession openSession(LanguageSessionContext context) {
        return new LanguageSession(CapabilityRegistry.builder()
            .add(LanguageCapabilities.HIGHLIGHTING, GroovySyntaxHighlighting::computeHighlighting)
            .add(SstCapabilities.DIAGNOSTICS, new GroovyDiagnosticsProvider())
            .build());
    }

    @Override
    public EditorOpenView open(Project project, DocumentId documentId, Path file) {
        var editor = new CodeEditorPane(project, documentId, file,
            CodeEditorConfig.fromLanguageSupport(project, file, this, null));
        return new EditorOpenView(editor, editor, languageId());
    }
}
