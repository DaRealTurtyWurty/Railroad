package dev.railroadide.railroad.ide.language.impl.groovy;

import dev.railroadide.railroad.ide.language.impl.GroovyLanguageSupport;
import dev.railroadide.railroad.ide.language.index.LanguageFileIndex;
import dev.railroadide.railroad.ide.language.index.ProjectLanguageIndex;
import dev.railroadide.railroad.ide.language.index.ProjectTypeIndex;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable index of the types declared by a project's Groovy sources.
 */
public final class GroovyProjectIndex implements ProjectLanguageIndex<GroovyProjectIndex.FileTypes>, ProjectTypeIndex {
    private final Map<Path, FileTypes> files;

    /**
     * Copies the indexed files.
     *
     * @param files indexed files keyed by path
     */
    public GroovyProjectIndex(Map<Path, FileTypes> files) {
        this.files = Map.copyOf(files);
    }

    @Override
    public String languageId() {
        return GroovyLanguageSupport.LANGUAGE_ID;
    }

    @Override
    public @Nullable FileTypes getFileIndex(Path path) {
        return files.get(path);
    }

    /**
     * Returns the indexed files.
     *
     * @return immutable files keyed by path
     */
    public Map<Path, FileTypes> files() {
        return files;
    }

    @Override
    public List<ProjectType> types(Runnable activityCheck) {
        List<ProjectType> types = new ArrayList<>();
        for (FileTypes file : files.values()) {
            activityCheck.run();
            types.addAll(file.types());
        }

        return List.copyOf(types);
    }

    /**
     * Returns an index with one file added or replaced.
     *
     * @param file indexed file
     * @return the updated index
     */
    public GroovyProjectIndex withFile(FileTypes file) {
        Map<Path, FileTypes> updated = new HashMap<>(files);
        updated.put(file.path(), file);
        return new GroovyProjectIndex(updated);
    }

    /**
     * Returns an index without one file.
     *
     * @param path file to remove
     * @return the updated index
     */
    public GroovyProjectIndex withoutFile(Path path) {
        if (!files.containsKey(path))
            return this;

        Map<Path, FileTypes> updated = new HashMap<>(files);
        updated.remove(path);
        return new GroovyProjectIndex(updated);
    }

    /**
     * The types one Groovy source declares.
     *
     * @param path source file
     * @param types declared types, empty when the file does not parse or is a script
     */
    public record FileTypes(Path path, List<ProjectType> types) implements LanguageFileIndex {
        /**
         * Validates the path and copies the types.
         *
         * @param path source file
         * @param types declared types, empty when the file does not parse or is a script
         */
        public FileTypes {
            Objects.requireNonNull(path, "path");
            types = List.copyOf(types);
        }
    }
}
