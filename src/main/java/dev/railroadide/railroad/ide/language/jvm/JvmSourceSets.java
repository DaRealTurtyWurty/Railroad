package dev.railroadide.railroad.ide.language.jvm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import org.jspecify.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The compile inputs of each source set in one imported JVM build, read from the workspace configuration that
 * {@link dev.railroadide.railroad.ide.ProjectWorkspaceConfiguration} publishes. Every JVM language resolves its
 * classpath, JDK and visible source roots through this view rather than parsing the settings itself.
 */
public final class JvmSourceSets {
    private static final LoadingCache<WorkspaceConfigurationSnapshot, JvmSourceSets> CACHE = CacheBuilder.newBuilder()
        .weakKeys().maximumSize(16).build(CacheLoader.from(JvmSourceSets::new));

    /**
     * The compile inputs of one source set.
     *
     * @param module identifier of the owning module
     * @param name source set name, such as {@code main} or {@code test}
     * @param visibleRoots source roots whose declarations the source set can see, including its own
     * @param classpath binary compile classpath in configured order
     * @param jdkHome JDK used to compile the source set, or {@code null} when unknown
     */
    public record Scope(
        String module,
        String name,
        Set<Path> visibleRoots,
        List<Path> classpath,
        Path jdkHome
    ) {
    }

    private static final Path RUNNING_JDK = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
    private final Map<Path, Scope> owners = new LinkedHashMap<>();
    private final Path importRoot;
    private final Path openRoot;
    private final List<Scope> scopes;
    private final Set<Path> outputs;

    private JvmSourceSets(WorkspaceConfigurationSnapshot configuration) {
        Map<String, String> settings = configuration.settings();
        openRoot = settings.containsKey("gradle.openRoot")
            ? lookupPath(Path.of(settings.get("gradle.openRoot")))
            : null;
        importRoot = settings.containsKey("gradle.importRoot")
            ? lookupPath(Path.of(settings.get("gradle.importRoot")))
            : null;
        record Input(String module, String name, String prefix, List<Path> roots) {
        }

        List<Input> inputs = new ArrayList<>();
        Map<Path, List<Path>> outputRoots = new HashMap<>();
        for (String module : configuration.moduleIds().stream().sorted().toList()) {
            String prefix = "module." + module + ".";
            for (String name : settings.getOrDefault(prefix + "sourceSets", "").lines().sorted().toList()) {
                String set = prefix + "set." + name + ".";
                List<Path> roots = paths(settings, set + "sources");
                inputs.add(new Input(module, name, set, roots));
                paths(settings, set + "outputs").forEach(output -> outputRoots.put(output, roots));
            }
        }

        outputs = Set.copyOf(outputRoots.keySet());
        List<Scope> selected = new ArrayList<>();
        for (Input input : inputs) {
            Set<Path> visible = new LinkedHashSet<>(input.roots());
            List<Path> classpath = new ArrayList<>();
            for (Path entry : paths(settings, input.prefix() + "classpath")) {
                List<Path> producer = outputRoots.get(entry);
                if (producer == null) {
                    classpath.add(entry);
                } else {
                    visible.addAll(producer);
                }
            }

            String jdk = settings.getOrDefault(input.prefix() + "jdkHome",
                settings.get("module." + input.module() + ".jdkHome"));
            var scope = new Scope(input.module(), input.name(), Set.copyOf(visible),
                List.copyOf(classpath), jdk == null ? null : Path.of(jdk));
            input.roots().forEach(path -> owners.putIfAbsent(path, scope));
            selected.add(scope);
        }

        scopes = List.copyOf(selected);
    }

    /**
     * Returns the source sets of an imported build, cached per configuration.
     *
     * @param configuration captured workspace configuration
     * @return source sets described by the configuration
     */
    public static JvmSourceSets of(WorkspaceConfigurationSnapshot configuration) {
        return CACHE.getUnchecked(configuration);
    }

    /**
     * Expands Windows short names for comparisons with source roots supplied by build tools.
     *
     * @param path path to compare
     * @return normalized lookup path
     */
    public static Path lookupPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (File.separatorChar == '\\' && absolute.toString().contains("~")) {
            try {
                return absolute.toFile().getCanonicalFile().toPath();
            } catch (IOException _) {
                return absolute;
            }
        }

        return absolute;
    }

    private Path importedPath(Path path) {
        Path normalized = lookupPath(path);
        return openRoot != null && importRoot != null && normalized.startsWith(openRoot)
            ? importRoot.resolve(openRoot.relativize(normalized))
            : normalized;
    }

    /**
     * Reports whether the configuration describes no source sets, such as before a build is imported.
     *
     * @return whether there are no source sets
     */
    public boolean isEmpty() {
        return scopes.isEmpty();
    }

    /**
     * Finds the source set whose source roots contain a file.
     *
     * @param path file or directory to look up
     * @return owning source set, or {@code null} when no source set owns the path
     */
    public Scope owner(Path path) {
        return owners.get(owningRoot(path));
    }

    private Path owningRoot(Path path) {
        if (path == null)
            return null;

        for (Path current = importedPath(path); current != null; current = current.getParent()) {
            if (owners.containsKey(current))
                return current;
        }

        return null;
    }

    /**
     * Finds the source set of a module that compiles against an archive, preferring {@code main}.
     *
     * @param module identifier of the importing module
     * @param archive binary on the classpath
     * @return importing source set, or {@code null} when none of the module's source sets uses the archive
     */
    public Scope importing(String module, Path archive) {
        if (archive == null || module == null)
            return null;

        return scopes.stream().filter(scope -> scope.module().equals(module) && scope.classpath().contains(archive))
            .min(Comparator.comparing(scope -> !scope.name().equals("main"))).orElse(null);
    }

    /**
     * Reports whether a source set can see the declarations of a file.
     *
     * @param scope source set resolving references
     * @param path source file declaring the referenced symbol
     * @return whether the file lies in one of the source set's visible roots
     */
    public boolean visible(Scope scope, Path path) {
        Path root = owningRoot(path);
        return root != null && scope.visibleRoots().contains(root);
    }

    /**
     * Returns the compiled output directories of every source set.
     *
     * @return normalized output directories
     */
    public Set<Path> outputs() {
        return outputs;
    }

    /**
     * The binary inputs a source file compiles against.
     *
     * @param module module owning the file, or {@code null} when no module owns it
     * @param classpath ordered binary roots
     * @param jdkHome JDK the file compiles against
     */
    public record CompileInputs(@Nullable String module, List<Path> classpath, Path jdkHome) {
    }

    /**
     * Selects the classpath and JDK of the source set owning a file, falling back to its module's, or the whole
     * workspace's when no module owns the file.
     *
     * @param configuration captured module settings
     * @param file source file, or {@code null} for the workspace fallback
     * @return the file's compile inputs
     */
    public static CompileInputs compileInputs(WorkspaceConfigurationSnapshot configuration, @Nullable Path file) {
        Scope owner = of(configuration).owner(file);
        if (owner != null)
            return new CompileInputs(owner.module(), owner.classpath(),
                owner.jdkHome() != null ? owner.jdkHome() : jdkHome(configuration));

        String module = module(configuration, file);
        return new CompileInputs(module, classpath(configuration, module), jdkHome(configuration));
    }

    /**
     * Collects normalized, distinct binary roots excluding workspace output directories, for one module or, when
     * none is selected, every module in sorted order. Within each module, compile classpath and module path roots
     * retain their configured order.
     *
     * @param configuration captured module settings
     * @param selectedModule module whose roots are collected, or {@code null} for every module
     * @return immutable ordered binary roots
     */
    public static List<Path> classpath(WorkspaceConfigurationSnapshot configuration, @Nullable String selectedModule) {
        Set<Path> roots = new LinkedHashSet<>();
        Set<Path> outputs = new HashSet<>(of(configuration).outputs());
        for (String module : configuration.moduleIds()) {
            for (String kind : List.of("output", "testOutput")) {
                String output = configuration.settings().get("module." + module + "." + kind);
                if (output != null && !output.isBlank()) {
                    outputs.add(Path.of(output).toAbsolutePath().normalize());
                }
            }
        }

        List<String> modules = selectedModule == null
            ? configuration.moduleIds().stream().sorted().toList()
            : List.of(selectedModule);
        for (String module : modules) {
            String prefix = "module." + module + ".";
            Map<String, String> settings = configuration.settings();
            List<String> kinds = settings.containsKey(prefix + "classpath")
                || settings.containsKey(prefix + "modulePath")
                    ? List.of("classpath", "modulePath")
                    : List.of("dependencies");
            for (String kind : kinds) {
                String value = settings.get(prefix + kind);
                if (value != null) {
                    value.lines().filter(line -> !line.isBlank()).map(Path::of)
                        .map(path -> path.toAbsolutePath().normalize()).filter(path -> !outputs.contains(path))
                        .forEach(roots::add);
                }
            }
        }

        return List.copyOf(roots);
    }

    /**
     * Selects the first configured JDK home in sorted module order, then the Gradle or running JDK.
     *
     * @param configuration captured module settings
     * @return absolute normalized JDK home
     */
    public static Path jdkHome(WorkspaceConfigurationSnapshot configuration) {
        String home = configuration.moduleIds().stream().sorted()
            .map(module -> configuration.settings().get("module." + module + ".jdkHome"))
            .filter(Objects::nonNull).filter(value -> !value.isBlank()).findFirst()
            .orElse(configuration.settings().get("gradle.jdkHome"));
        return home == null || home.isBlank() ? RUNNING_JDK : Path.of(home).toAbsolutePath().normalize();
    }

    /**
     * Collects the source roots declared by every module.
     *
     * @param configuration captured module settings
     * @return normalized source roots
     */
    public static Set<Path> sourceRoots(WorkspaceConfigurationSnapshot configuration) {
        Set<Path> roots = new HashSet<>();
        for (String module : configuration.moduleIds()) {
            roots.addAll(sourceRoots(configuration, module));
        }

        return Set.copyOf(roots);
    }

    /**
     * Relativizes a path against its closest configured JVM source root.
     *
     * @param configuration captured module settings
     * @param path file or directory to look up
     * @return path relative to its source root, or empty when no configured root contains it
     */
    public static Optional<Path> relativeSourcePath(WorkspaceConfigurationSnapshot configuration, Path path) {
        Path imported = of(configuration).importedPath(path);
        Set<Path> roots = sourceRoots(configuration);
        for (Path current = imported; current != null; current = current.getParent()) {
            if (roots.contains(current))
                return Optional.of(current.relativize(imported));
        }

        return Optional.empty();
    }

    private static List<Path> sourceRoots(WorkspaceConfigurationSnapshot configuration, String module) {
        String sources = configuration.settings().getOrDefault("module." + module + ".sources", "");
        return sources.lines().filter(line -> !line.isBlank())
            .map(line -> line.replaceFirst(":(?:test:)?generated=(?:true|false)$", ""))
            .map(Path::of).map(JvmSourceSets::lookupPath).toList();
    }

    /**
     * Finds the module whose source roots, or failing that whose directory, most specifically contain a path.
     *
     * @param configuration captured module settings
     * @param path file to look up, or {@code null}
     * @return owning module, or {@code null} when no module with binary inputs contains the path
     */
    public static @Nullable String module(WorkspaceConfigurationSnapshot configuration, @Nullable Path path) {
        if (path == null)
            return null;

        Path file = lookupPath(path);
        String owner = null;
        int bestSource = -1;
        int bestDirectory = -1;
        for (String module : configuration.moduleIds().stream().sorted().toList()) {
            String prefix = "module." + module + ".";
            Map<String, String> settings = configuration.settings();
            if (!settings.containsKey(prefix + "classpath") && !settings.containsKey(prefix + "modulePath")
                && !settings.containsKey(prefix + "dependencies"))
                continue;

            String directory = settings.get(prefix + "directory");
            int directoryDepth = directory == null || directory.isBlank()
                ? -1
                : matchingDepth(file, lookupPath(Path.of(directory)));
            int sourceDepth = sourceRoots(configuration, module).stream()
                .mapToInt(root -> matchingDepth(file, root)).max().orElse(-1);
            if (sourceDepth > bestSource || sourceDepth == bestSource && directoryDepth > bestDirectory) {
                owner = module;
                bestSource = sourceDepth;
                bestDirectory = directoryDepth;
            }
        }

        return owner;
    }

    private static int matchingDepth(Path file, Path root) {
        return file.startsWith(root) ? root.getNameCount() : -1;
    }

    private static List<Path> paths(Map<String, String> settings, String key) {
        return settings.getOrDefault(key, "").lines().filter(line -> !line.isBlank()).map(Path::of)
            .map(JvmSourceSets::lookupPath).distinct().toList();
    }
}
