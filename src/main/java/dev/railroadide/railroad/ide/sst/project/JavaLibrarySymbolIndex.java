package dev.railroadide.railroad.ide.sst.project;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.ide.sst.request.internal.ActivityCheckpoint;
import java.util.concurrent.CancellationException;
import dev.railroadide.railroad.ide.classparser.ClassScanException;
import dev.railroadide.railroad.ide.classparser.ClassStubParser;
import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Binary symbol index built from class directories, JAR archives, and JMOD archives.
 */
public final class JavaLibrarySymbolIndex extends JavaStubSymbolIndex {
    private JavaLibrarySymbolIndex(
        Map<String, ClassStub> classStubsByQualifiedName,
        Map<String, Path> sourceByQualifiedName,
        Runnable activityCheck
    ) {
        super(classStubsByQualifiedName, sourceByQualifiedName, activityCheck);
    }

    /**
     * Scans unique library roots and indexes readable class files, logging individual scan failures.
     *
     * @param roots the class directories, JARs, or JMODs to scan; unavailable roots are skipped
     * @return the symbol index for successfully parsed library classes
     */
    public static JavaLibrarySymbolIndex build(List<Path> roots) {
        return build(roots, ActivityCheckpoint::checkInterrupted);
    }

    /**
     * Scans captured binary roots with cooperative cancellation and deadline checks.
     *
     * @param roots binary roots in lookup order
     * @param activityCheck request activity check
     * @return complete index; cancelled scans never publish a partial index
     */
    public static JavaLibrarySymbolIndex build(List<Path> roots, Runnable activityCheck) {
        Objects.requireNonNull(activityCheck, "activityCheck").run();
        Map<String, ClassStub> stubsByQualifiedName = new LinkedHashMap<>();
        Map<String, Path> sourceByQualifiedName = new LinkedHashMap<>();
        Set<Path> uniqueRoots = new LinkedHashSet<>(roots);
        for (Path root : uniqueRoots) {
            activityCheck.run();
            scanRoot(root, stubsByQualifiedName, sourceByQualifiedName, activityCheck);
        }

        activityCheck.run();
        return new JavaLibrarySymbolIndex(stubsByQualifiedName, sourceByQualifiedName, activityCheck);
    }

    private static void scanRoot(
        Path root,
        Map<String, ClassStub> stubsByQualifiedName,
        Map<String, Path> sourceByQualifiedName,
        Runnable activityCheck
    ) {
        if (root == null || Files.notExists(root) || !Files.isReadable(root))
            return;

        if (Files.isDirectory(root)) {
            scanDirectory(root, stubsByQualifiedName, sourceByQualifiedName, activityCheck);
            return;
        }

        String fileName = root.getFileName() == null ? "" : root.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".jar") || fileName.endsWith(".jmod")) {
            scanArchive(root, stubsByQualifiedName, sourceByQualifiedName, activityCheck);
        }
    }

    private static void scanDirectory(
        Path root,
        Map<String, ClassStub> stubsByQualifiedName,
        Map<String, Path> sourceByQualifiedName,
        Runnable activityCheck
    ) {
        try (var paths = Files.walk(root)) {
            paths.peek(_ -> activityCheck.run()).filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".class"))
                .filter(path -> !isDescriptorClass(path.getFileName().toString()))
                .forEach(
                    path -> parseClassFile(path, root, stubsByQualifiedName, sourceByQualifiedName, activityCheck));
        } catch (IOException exception) {
            Railroad.LOGGER.error("Error scanning library directory {}", root, exception);
        }
    }

    private static void parseClassFile(
        Path classFile,
        Path origin,
        Map<String, ClassStub> stubsByQualifiedName,
        Map<String, Path> sourceByQualifiedName,
        Runnable activityCheck
    ) {
        activityCheck.run();
        try {
            indexStub(ClassStubParser.parse(classFile), origin, stubsByQualifiedName, sourceByQualifiedName,
                activityCheck);
        } catch (ClassScanException exception) {
            Railroad.LOGGER.warn("Ignoring unreadable class file {}", classFile, exception);
        }
    }

    private static void scanArchive(
        Path archive,
        Map<String, ClassStub> stubsByQualifiedName,
        Map<String, Path> sourceByQualifiedName,
        Runnable activityCheck
    ) {
        try (JarFile jarFile = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                activityCheck.run();
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory())
                    continue;

                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || isDescriptorClass(entryName))
                    continue;

                try (InputStream input = jarFile.getInputStream(entry)) {
                    indexStub(ClassStubParser.parse(new ClassReader(input)), archive, stubsByQualifiedName,
                        sourceByQualifiedName, activityCheck);
                } catch (CancellationException exception) {
                    throw exception;
                } catch (Exception exception) {
                    Railroad.LOGGER.warn("Ignoring unreadable class entry {} in {}", entryName, archive, exception);
                }
            }
        } catch (IOException exception) {
            Railroad.LOGGER.error("Error scanning library archive {}", archive, exception);
        }
    }

    private static void indexStub(
        ClassStub stub,
        Path origin,
        Map<String, ClassStub> stubsByQualifiedName,
        Map<String, Path> sourceByQualifiedName,
        Runnable activityCheck
    ) {
        activityCheck.run();
        String qualifiedName = stub.getFullName();
        if (qualifiedName == null || qualifiedName.isBlank())
            return;

        stubsByQualifiedName.putIfAbsent(qualifiedName, stub);
        sourceByQualifiedName.putIfAbsent(qualifiedName, origin);
    }

    private static boolean isDescriptorClass(String name) {
        return name.endsWith("module-info.class") || name.endsWith("package-info.class");
    }
}
