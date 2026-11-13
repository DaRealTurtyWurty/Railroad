package dev.railroadide.railroad.ide.language;

import dev.railroadide.railroad.ide.language.impl.PlainTextLanguageSupport;
import dev.railroadide.railroad.ide.language.index.LanguageNotSupportedException;
import dev.railroadide.railroad.ide.sst.provider.api.*;
import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorConfig;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorPane;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.utility.FileUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import dev.railroadide.railroad.ide.sst.document.api.ContentKind;
import javafx.scene.control.Label;

/** Host presentation and storage adapter over the single language runtime. */
public final class LanguageSupportRegistry {
    private static final LanguageRuntime RUNTIME = new LanguageRuntime();

    private LanguageSupportRegistry() {
    }

    public static LanguageRuntime runtime() {
        return RUNTIME;
    }

    public static void register(LanguageSupport support) {
        RUNTIME.register("railroad", support);
    }

    public static void registerAll(Collection<? extends LanguageSupport> supports) {
        supports.forEach(LanguageSupportRegistry::register);
    }

    public static Optional<LanguageSupport> get(String languageId) {
        Objects.requireNonNull(languageId, "languageId");
        return RUNTIME.all().stream().filter(contribution -> contribution.id().value().equals(languageId)
            || hostLanguageId(contribution).equals(languageId)).findFirst().map(LanguageSupportRegistry::present);
    }

    public static LanguageSupport getExpected(String languageId) {
        return get(languageId).orElseThrow(() -> new LanguageNotSupportedException(languageId));
    }

    public static boolean contains(String languageId) {
        return get(languageId).isPresent();
    }

    public static Optional<LanguageSupport> find(Path path) {
        return RUNTIME.find(path).map(LanguageSupportRegistry::present);
    }

    public static String resolveLanguageId(Path path) {
        Objects.requireNonNull(path, "path");
        return find(path).map(LanguageSupport::languageId)
            .orElseGet(() -> Files.isRegularFile(path) && FileUtils.isBinaryFile(path)
                ? "binary"
                : PlainTextLanguageSupport.INSTANCE.languageId());
    }

    public static List<LanguageSupport> all() {
        return RUNTIME.all().stream().map(LanguageSupportRegistry::present).toList();
    }

    public static void clear() {
        RUNTIME.clear();
    }

    public static void unregister(String languageId) {
        get(languageId).ifPresent(support -> RUNTIME.unregister(support.id()));
    }

    /** Built-in storage names predate namespaced registration; independent languages retain their full ID. */
    public static String hostLanguageId(LanguageContribution contribution) {
        return contribution instanceof LanguageSupport support ? support.languageId() : contribution.id().value();
    }

    private static LanguageSupport present(LanguageContribution contribution) {
        return contribution instanceof LanguageSupport support ? support : new ContributionEditor(contribution);
    }

    private record ContributionEditor(LanguageContribution contribution) implements LanguageSupport {
        @Override
        public LanguageId id() {
            return contribution.id();
        }

        @Override
        public String languageId() {
            return contribution.id().value();
        }

        @Override
        public String displayName() {
            return contribution.displayName();
        }

        @Override
        public Set<String> fileExtensions() {
            return Set.of();
        }

        @Override
        public LanguageMatcher matcher() {
            return contribution.matcher();
        }

        @Override
        public int priority() {
            return contribution.priority();
        }

        @Override
        public Set<CapabilityKey<?>> supportedCapabilities() {
            return contribution.supportedCapabilities();
        }

        @Override
        public LanguageSession openSession(LanguageSessionContext context) {
            return contribution.openSession(context);
        }

        @Override
        public ContentKind contentKind() {
            return contribution.contentKind();
        }

        @Override
        public EditorOpenView open(Project project, DocumentId documentId, Path file) {
            if (contentKind() != ContentKind.TEXT)
                return new EditorOpenView(new Label("Binary document: " + file.getFileName()),
                    null, languageId());
            var editor = new CodeEditorPane(project, documentId, file,
                CodeEditorConfig.fromLanguageSupport(project, file, this, null)) {
                @Override
                protected boolean shouldRequestSignatureHelp(String text, int caret, boolean textChanged) {
                    return textChanged;
                }
            };
            return new EditorOpenView(editor, editor, languageId());
        }
    }
}
