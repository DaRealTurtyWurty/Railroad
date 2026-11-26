package dev.railroadide.railroad.ide.sst.project;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.ide.classparser.ClassStubParser;
import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.language.jvm.JvmClassDeclaration;
import dev.railroadide.railroad.ide.language.jvm.JvmClassDeclaration.Member;
import dev.railroadide.railroad.ide.sst.document.api.DocumentOrigin;
import dev.railroadide.railroad.ide.sst.document.api.DocumentUri;
import dev.railroadide.railroad.ide.sst.document.api.Location.TextLocation;
import dev.railroadide.railroad.ide.sst.java.api.JavaSymbolIds;
import dev.railroadide.railroad.ide.sst.java.api.JavaSymbolKind;
import dev.railroadide.railroad.ide.sst.semantic.api.SymbolDescriptor;
import dev.railroadide.railroad.ide.sst.semantic.api.SymbolId;
import dev.railroadide.railroad.ide.sst.semantic.api.SymbolKindId;
import dev.railroadide.railroad.ide.sst.semantic.api.SymbolLocation;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a Java symbol index from the classes other JVM languages declare. Their class files are read like library
 * classes under a virtual address of their own, and their declarations navigate to the declaring source.
 */
public final class JavaForeignSymbolIndex {
    private static final String BINARY_SCHEME = "railroad-jvm-declaration";
    private static final SymbolKindId FIELD = SymbolKindId.from(JavaSymbolKind.FIELD);
    private static final SymbolKindId METHOD = SymbolKindId.from(JavaSymbolKind.METHOD);
    private static final SymbolKindId CONSTRUCTOR = SymbolKindId.from(JavaSymbolKind.CONSTRUCTOR);

    private JavaForeignSymbolIndex() {
    }

    /**
     * Reports whether an address names a class that another JVM language declares.
     *
     * @param uri address of a binary declaration
     * @return whether the class comes from this index
     */
    public static boolean isForeign(DocumentUri uri) {
        return BINARY_SCHEME.equals(uri.value().getScheme());
    }

    /**
     * Indexes class declarations, keeping the first declaration of each class name and skipping unreadable ones.
     *
     * @param declarations classes declared by other JVM languages
     * @param activityCheck callback that throws once the request is cancelled or expires
     * @return an index of the declared classes
     */
    public static JavaSymbolIndex of(List<JvmClassDeclaration> declarations, Runnable activityCheck) {
        Map<String, ClassStub> stubs = new LinkedHashMap<>();
        Map<String, Path> origins = new HashMap<>();
        Map<String, DocumentUri> locations = new HashMap<>();
        Map<SymbolId, List<SymbolLocation>> alternates = new HashMap<>();
        for (JvmClassDeclaration declaration : declarations) {
            activityCheck.run();
            ClassStub stub;
            try {
                stub = BinaryStubPublication.copy(ClassStubParser.parse(new ClassReader(declaration.classFile())));
            } catch (RuntimeException exception) {
                Railroad.LOGGER.warn("Ignoring an unreadable class declared in {}", declaration.source(), exception);
                continue;
            }

            String name = stub.getFullName();
            if (stubs.putIfAbsent(name, stub) != null)
                continue;

            DocumentUri binary = DocumentUri.virtual(BINARY_SCHEME, name.replace('.', '/') + ".class");
            origins.put(name, declaration.source().filePath().orElse(Path.of(name)));
            locations.put(name, binary);
            sourceLocations(stub, binary, declaration, alternates, activityCheck);
        }

        return new JavaStubSymbolIndex(stubs, origins, locations, alternates, activityCheck);
    }

    /**
     * Matches the class's declarations to the source locations of its members by name, parameter count and order.
     */
    private static void sourceLocations(
        ClassStub stub,
        DocumentUri binary,
        JvmClassDeclaration declaration,
        Map<SymbolId, List<SymbolLocation>> alternates,
        Runnable activityCheck
    ) {
        Map<String, List<Member>> members = new HashMap<>();
        for (Member member : declaration.members()) {
            members.computeIfAbsent(member.name() + "/" + member.parameterCount(), _ -> new ArrayList<>())
                .add(member);
        }

        SymbolId type = JavaSymbolIds.type(stub.getFullName());
        Map<String, Integer> seen = new HashMap<>();
        var shard = JavaBinarySymbolExtractor.extractDeclarations(stub, binary, Map.of(),
            new JavaBinarySymbolExtractor.TypeReferences(), activityCheck);
        for (SymbolDescriptor symbol : shard.declarations()) {
            activityCheck.run();
            if (symbol.id().equals(type)) {
                put(alternates, symbol.id(), declaration.source(), declaration.declaration(), declaration.selection());
                continue;
            }

            String key;
            if (symbol.kind().equals(FIELD)) {
                key = symbol.simpleName() + "/-1";
            } else if (symbol.kind().equals(METHOD) || symbol.kind().equals(CONSTRUCTOR)) {
                int parameters = symbol.signature() == null ? 0 : symbol.signature().parameters().size();
                key = (symbol.kind().equals(CONSTRUCTOR) ? Member.CONSTRUCTOR : symbol.simpleName()) + "/" + parameters;
            } else
                continue;

            int ordinal = seen.merge(key, 1, Integer::sum) - 1;
            List<Member> candidates = members.get(key);
            if (candidates != null && ordinal < candidates.size()) {
                Member member = candidates.get(ordinal);
                put(alternates, symbol.id(), declaration.source(), member.declaration(), member.selection());
            }
        }
    }

    private static void put(
        Map<SymbolId, List<SymbolLocation>> alternates,
        SymbolId id,
        DocumentUri source,
        @Nullable TextLocation declaration,
        @Nullable TextLocation selection
    ) {
        if (declaration != null) {
            alternates.put(id, List.of(new SymbolLocation(source, DocumentOrigin.original(source), declaration,
                selection, true)));
        }
    }
}
