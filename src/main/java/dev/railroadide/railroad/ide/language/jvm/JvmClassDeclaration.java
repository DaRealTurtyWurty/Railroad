package dev.railroadide.railroad.ide.language.jvm;

import dev.railroadide.railroad.ide.sst.document.api.DocumentUri;
import dev.railroadide.railroad.ide.sst.document.api.Location.TextLocation;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A class declared by a JVM language's source, described as a JVM class file so that any other JVM language can
 * read it as it would a compiled library class. The class file needs signatures only: method bodies may be omitted.
 *
 * @param classFile class file bytes; copied on construction and on access
 * @param source source file declaring the class
 * @param declaration whole declaration in the captured source revision, or {@code null} when unknown
 * @param selection name of the class in the captured source revision, or {@code null} when unknown
 * @param members source locations of the fields, methods and constructors in the class file
 */
public record JvmClassDeclaration(
    byte[] classFile,
    DocumentUri source,
    @Nullable TextLocation declaration,
    @Nullable TextLocation selection,
    List<Member> members
) {
    /**
     * Copies the class file and member list and validates the required fields.
     *
     * @param classFile class file bytes; copied on construction and on access
     * @param source source file declaring the class
     * @param declaration whole declaration in the captured source revision, or {@code null} when unknown
     * @param selection name of the class in the captured source revision, or {@code null} when unknown
     * @param members source locations of the fields, methods and constructors in the class file
     */
    public JvmClassDeclaration {
        classFile = Objects.requireNonNull(classFile, "classFile").clone();
        Objects.requireNonNull(source, "source");
        members = List.copyOf(members);
    }

    @Override
    public byte[] classFile() {
        return classFile.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "JvmClassDeclaration[" + source + ", " + classFile.length + " bytes, " + members.size() + " members]";
    }

    /**
     * The source location of one member of the class file. A member is identified by its name, its parameter count
     * and its position among the class file's members with the same name and parameter count.
     *
     * @param name member name; {@code <init>} for constructors
     * @param parameterCount number of parameters, or {@code -1} for a field
     * @param declaration whole declaration in the captured source revision
     * @param selection member name in the captured source revision, or {@code null} when unknown
     */
    public record Member(String name, int parameterCount, TextLocation declaration, @Nullable TextLocation selection) {
        /**
         * The name the class file uses for constructors.
         */
        public static final String CONSTRUCTOR = "<init>";

        /**
         * Validates the member identity and location.
         *
         * @param name member name; {@code <init>} for constructors
         * @param parameterCount number of parameters, or {@code -1} for a field
         * @param declaration whole declaration in the captured source revision
         * @param selection member name in the captured source revision, or {@code null} when unknown
         */
        public Member {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(declaration, "declaration");
            if (parameterCount < -1)
                throw new IllegalArgumentException("Parameter count must be -1 for fields or non-negative");
        }
    }
}
