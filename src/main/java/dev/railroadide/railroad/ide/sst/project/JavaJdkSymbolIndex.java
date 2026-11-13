package dev.railroadide.railroad.ide.sst.project;

import dev.railroadide.railroad.ide.classparser.stub.ClassStub;
import dev.railroadide.railroad.ide.sst.request.internal.ActivityCheckpoint;
import dev.railroadide.railroad.ide.sst.impl.java.JavaSemanticAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binary symbol index for a selected JDK or the currently running Java runtime.
 */
public final class JavaJdkSymbolIndex extends JavaStubSymbolIndex {
    private JavaJdkSymbolIndex(
        Map<String, ClassStub> classStubsByQualifiedName,
        Map<String, Path> sourceByQualifiedName,
        Runnable activityCheck
    ) {
        super(classStubsByQualifiedName, sourceByQualifiedName, activityCheck);
    }

    /**
     * Indexes a JDK's runtime archive or modules, falling back to the current runtime when roots are unavailable.
     *
     * @param jdkHome the JDK installation directory, or {@code null} to use the current runtime
     * @return the selected JDK index, or a current-runtime index on fallback
     */
    public static JavaJdkSymbolIndex build(Path jdkHome) {
        return build(jdkHome, ActivityCheckpoint::checkInterrupted);
    }

    /**
     * Indexes a selected JDK with cooperative controls during scanning and lookup-table construction.
     *
     * @param jdkHome JDK home, or null for the running runtime
     * @param activityCheck request activity check
     * @return complete immutable binary index
     */
    public static JavaJdkSymbolIndex build(Path jdkHome, Runnable activityCheck) {
        activityCheck.run();
        if (jdkHome == null || Files.notExists(jdkHome))
            return fromCurrentRuntime(activityCheck);

        List<Path> roots = new ArrayList<>();
        Path rtJar = jdkHome.resolve("jre/lib/rt.jar");
        if (!Files.isRegularFile(rtJar)) {
            rtJar = jdkHome.resolve("lib/rt.jar");
        }
        if (Files.isRegularFile(rtJar)) {
            roots.add(rtJar);
        } else {
            Path jmods = jdkHome.resolve("jmods");
            if (Files.isDirectory(jmods)) {
                try (var paths = Files.list(jmods)) {
                    roots.addAll(
                        paths.filter(path -> path.getFileName().toString().endsWith(".jmod")).sorted().toList());
                } catch (IOException _) {
                    return fromCurrentRuntime(activityCheck);
                }
            }
        }

        if (roots.isEmpty())
            return fromCurrentRuntime(activityCheck);

        JavaLibrarySymbolIndex index = JavaLibrarySymbolIndex.build(roots, activityCheck);
        Map<String, ClassStub> classStubsByQualifiedName = new LinkedHashMap<>(index.classStubsByQualifiedName());
        Map<String, Path> sourceByQualifiedName = new LinkedHashMap<>();
        classStubsByQualifiedName.keySet().forEach(qualifiedName -> {
            ActivityCheckpoint.poll(activityCheck);
            sourceByQualifiedName.put(qualifiedName, jdkHome);
        });
        return new JavaJdkSymbolIndex(classStubsByQualifiedName, sourceByQualifiedName, activityCheck);
    }

    /**
     * Builds an index from the cached standard-library class stubs of the current runtime.
     *
     * @return the current-runtime symbol index
     */
    public static JavaJdkSymbolIndex fromCurrentRuntime() {
        return fromCurrentRuntime(ActivityCheckpoint::checkInterrupted);
    }

    /**
     * Reuses or scans runtime stubs while checking the current request.
     *
     * @param activityCheck request activity check
     * @return complete immutable runtime index
     */
    public static JavaJdkSymbolIndex fromCurrentRuntime(Runnable activityCheck) {
        activityCheck.run();
        Map<String, ClassStub> classStubsByQualifiedName = new LinkedHashMap<>(
            JavaSemanticAnalyzer.loadJdkClassStubsByQualifiedName(activityCheck));
        Map<String, Path> sourceByQualifiedName = new LinkedHashMap<>();
        Path runtimeHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        classStubsByQualifiedName.keySet()
            .forEach(qualifiedName -> {
                ActivityCheckpoint.poll(activityCheck);
                sourceByQualifiedName.put(qualifiedName, runtimeHome);
            });
        return new JavaJdkSymbolIndex(classStubsByQualifiedName, sourceByQualifiedName, activityCheck);
    }
}
