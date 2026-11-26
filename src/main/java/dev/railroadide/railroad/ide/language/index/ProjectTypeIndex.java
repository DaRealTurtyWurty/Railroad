package dev.railroadide.railroad.ide.language.index;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A project language index that can list the types its sources declare, for language-neutral features such as
 * Go to Class.
 */
public interface ProjectTypeIndex {
    /**
     * Lists the declared types, including nested ones.
     *
     * @param activityCheck callback that throws once the request is cancelled
     * @return declared types
     */
    List<ProjectType> types(Runnable activityCheck);

    /**
     * Kind of a declared type, as presented to the user.
     */
    enum Kind {
        CLASS,
        INTERFACE,
        ENUM,
        RECORD,
        ANNOTATION,
        TRAIT
    }

    /**
     * A type declared by a project source.
     *
     * @param simpleName type name
     * @param qualifiedName fully qualified name, with nested types separated by dots
     * @param kind declaration kind
     * @param file declaring source file
     * @param nameOffset offset of the name when the file was indexed, or {@code -1}
     */
    record ProjectType(String simpleName, String qualifiedName, Kind kind, Path file, int nameOffset) {
        /**
         * Validates the required fields.
         *
         * @param simpleName type name
         * @param qualifiedName fully qualified name, with nested types separated by dots
         * @param kind declaration kind
         * @param file declaring source file
         * @param nameOffset offset of the name when the file was indexed, or {@code -1}
         */
        public ProjectType {
            Objects.requireNonNull(simpleName, "simpleName");
            Objects.requireNonNull(qualifiedName, "qualifiedName");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(file, "file");
        }
    }
}
