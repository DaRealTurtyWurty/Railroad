package dev.railroadide.railroad.ide.language;

import dev.railroadide.railroad.ide.sst.document.api.DocumentId;
import dev.railroadide.railroad.ide.sst.provider.api.*;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import java.io.File;
import java.nio.file.Path;
import java.util.Set;

/** Host editor presentation for a language contribution; optional features live in its session. */
public interface LanguageSupport extends LanguageContribution {
    /** Host storage identity. Built-ins retain their existing persisted index names. */
    String languageId();

    Set<String> fileExtensions();

    @Override
    default LanguageId id() {
        String id = languageId();
        return new LanguageId(id.contains(":") ? id : "railroad:" + id);
    }

    @Override
    default LanguageMatcher matcher() {
        return LanguageMatcher.any(fileExtensions().stream().map(LanguageMatcher::extension)
            .toArray(LanguageMatcher[]::new));
    }

    default boolean supports(Path path) {
        return matcher().match(new LanguageMatchInput(path, new byte[0])) > 0;
    }

    default boolean supports(File file) {
        return supports(file.toPath());
    }

    EditorOpenView open(Project project, DocumentId documentId, Path file);

    @Override
    default LanguageSession openSession(LanguageSessionContext context) {
        return new LanguageSession(CapabilityRegistry.empty());
    }
}
