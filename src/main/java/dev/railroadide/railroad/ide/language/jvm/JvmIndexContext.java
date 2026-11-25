package dev.railroadide.railroad.ide.language.jvm;

import dev.railroadide.railroad.ide.language.index.LanguageIndexContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Indexing inputs of one JVM language: the shared project model under that language's identifier.
 *
 * @param languageId host identifier of the language being indexed
 * @param model resolved project model
 */
public record JvmIndexContext(String languageId, JvmProjectModel model) implements LanguageIndexContext {
    /**
     * Validates the language identifier and model.
     *
     * @param languageId host identifier of the language being indexed
     * @param model resolved project model
     */
    public JvmIndexContext {
        Objects.requireNonNull(languageId, "languageId");
        Objects.requireNonNull(model, "model");
    }

    @Override
    public List<Path> sourceRoots() {
        return model.sourceRoots();
    }

    @Override
    public List<Path> generatedRoots() {
        return model.generatedRoots();
    }

    @Override
    public List<Path> dependencyRoots() {
        return model.dependencyRoots();
    }
}
