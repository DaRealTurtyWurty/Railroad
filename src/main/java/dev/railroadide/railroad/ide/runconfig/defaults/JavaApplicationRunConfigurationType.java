package dev.railroadide.railroad.ide.runconfig.defaults;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.debug.breakpoint.SourceBreakpoint;
import dev.railroadide.railroad.debug.jdi.JdiDebugSession;
import dev.railroadide.railroad.debug.model.DebugEndpoint;
import dev.railroadide.railroad.debug.model.DebugFrame;
import dev.railroadide.railroad.debug.model.DebugSessionEvent;
import dev.railroadide.railroad.debug.source.DebugSource;
import dev.railroadide.railroad.debug.source.DependencySourceIndex;
import dev.railroadide.railroad.debug.source.SourceResolver;
import dev.railroadide.railroad.ide.runconfig.RunConfiguration;
import dev.railroadide.railroad.ide.runconfig.RunConfigurationType;
import dev.railroadide.railroad.ide.runconfig.defaults.data.JavaApplicationRunConfigurationData;
import dev.railroadide.railroad.java.JDK;
import dev.railroadide.railroad.java.JDKManager;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.project.facet.FacetManager;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.UnknownNullability;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Launches Java main classes for run or debug sessions and tracks their processes.
 */
public class JavaApplicationRunConfigurationType extends RunConfigurationType<JavaApplicationRunConfigurationData> {
    private final Map<RunConfiguration<?>, Process> runningProcesses = new ConcurrentHashMap<>();

    /**
     * Creates the Java application type with its localized label and application icon.
     */
    public JavaApplicationRunConfigurationType() {
        super("railroad.runconfig.java_application", FontAwesomeSolid.BOX, Color.web("#f89820"));
    }

    @Override
    public CompletableFuture<Void> run(
        Project project,
        RunConfiguration<JavaApplicationRunConfigurationData> configuration
    ) {
        return execute(project, configuration, false).whenComplete((_, throwable) -> {
            if (throwable != null) {
                Railroad.LOGGER.error("Failed to start run session for configuration: {}",
                    configuration.data().getName(), throwable);
            }
        });
    }

    @Override
    public CompletableFuture<Void> debug(
        Project project,
        RunConfiguration<JavaApplicationRunConfigurationData> configuration
    ) {
        return execute(project, configuration, true).whenComplete((_, throwable) -> {
            if (throwable != null) {
                Railroad.LOGGER.error("Failed to start debug session for configuration: {}",
                    configuration.data().getName(), throwable);
            }
        });
    }

    @Override
    public boolean isDebuggingSupported(
        Project project,
        RunConfiguration<JavaApplicationRunConfigurationData> configuration
    ) {
        return true;
    }

    @Override
    public CompletableFuture<Void> stop(
        Project project,
        RunConfiguration<JavaApplicationRunConfigurationData> configuration
    ) {
        Process process = runningProcesses.get(configuration);
        if (process != null && process.isAlive()) {
            process.destroy();
            process.onExit().thenRun(() -> runningProcesses.remove(configuration));
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isRunning(Project project, RunConfiguration<JavaApplicationRunConfigurationData> configuration) {
        Process process = runningProcesses.get(configuration);
        return process != null && process.isAlive();
    }

    @Override
    public JavaApplicationRunConfigurationData createDataInstance(@UnknownNullability Project project) {
        var data = new JavaApplicationRunConfigurationData();
        data.setName("New Java Application");
        data.setJdk(/* project.getJDKManager().getDefaultJDK() */ JDKManager.getDefaultJDK()); // TODO
        data.setWorkingDirectory(project.getPath());
        return data;
    }

    @Override
    public Class<JavaApplicationRunConfigurationData> getDataClass() {
        return JavaApplicationRunConfigurationData.class;
    }

    private CompletableFuture<Void> execute(
        Project project,
        RunConfiguration<JavaApplicationRunConfigurationData> configuration,
        boolean debug
    ) {
        JavaApplicationRunConfigurationData data = configuration.data();
        final JDK jdk = data.getJdk();
        final String mainClass = normalizeMainClass(data.getMainClass());
        final Path workingDirectory = data.getWorkingDirectory();
        final boolean buildBeforeRun = data.isBuildBeforeRun();
        final String[] classpathEntries = sanitizeClasspathEntries(data.getClasspathEntries());
        final String[] programArguments = data.getProgramArguments();
        final String[] vmOptions = data.getVmOptions();
        final Map<String, String> environmentVariables = data.getEnvironmentVariables() == null
            ? Map.of()
            : data.getEnvironmentVariables();

        if (jdk == null)
            return CompletableFuture.failedFuture(new IllegalStateException("JDK is not specified"));

        if (mainClass == null || mainClass.isBlank())
            return CompletableFuture.failedFuture(new IllegalStateException("Main class is not specified"));

        if (classpathEntries.length == 0)
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Classpath is empty. Add compiled output directories to the Java application run configuration."));

        if (workingDirectory == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Working directory is not specified"));

        if (Files.notExists(workingDirectory) || !Files.isDirectory(workingDirectory))
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Working directory does not exist or is not a directory: " + workingDirectory));

        CompletableFuture<Void> buildFuture = CompletableFuture.completedFuture(null);
        if (buildBeforeRun) {
            if (project.hasFacet(FacetManager.GRADLE) || project.hasFacet(FacetManager.MAVEN)) {
                buildFuture = project.build(jdk).thenCompose(closeBuildConnection -> {
                    closeBuildConnection.run();
                    return CompletableFuture.completedFuture((Void) null);
                });
            } else {
                buildFuture = CompletableFuture.runAsync(() -> compilePlainJavaProject(
                    project,
                    jdk,
                    workingDirectory,
                    classpathEntries));
            }

            buildFuture = buildFuture.exceptionally(throwable -> {
                System.err.println("Build failed: " + throwable.getMessage());
                throw new IllegalStateException("Build failed before running application", throwable);
            });
        }

        return buildFuture.thenCompose(_ -> CompletableFuture.supplyAsync(() -> {
            try {
                final int debugPort = debug ? findFreePort() : -1;
                String[] command = buildCommand(jdk, mainClass, classpathEntries, programArguments, vmOptions, debug,
                    debugPort);
                Railroad.LOGGER.debug("Running Java application '{}' with command: {}", configuration.data().getName(),
                    String.join(" ", command));
                ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE);
                if (!environmentVariables.isEmpty()) {
                    builder.environment().putAll(environmentVariables);
                }

                Process process = builder.start();
                runningProcesses.put(configuration, process);

                // Start consuming output (must be done asynchronously)
                new ProcessOutputHandler(process, configuration.data().getName()).run();

                if (debug && debugPort > 0) {
                    SourceResolver sourceResolver = createSourceResolver(project);

                    var breakpoint = new SourceBreakpoint(
                        UUID.randomUUID(),
                        new DebugSource.FileSource(
                            project.getPath()
                                .resolve("src/main/java/com/example/Main.java")),
                        12,
                        true);
                    List<SourceBreakpoint> breakpoints = List.of(breakpoint);

                    Services.DEBUG_SERVICE.startSession(
                        new DebugEndpoint("127.0.0.1", debugPort),
                        sourceResolver,
                        breakpoints,
                        this::handleDebugEvent).exceptionally(throwable -> {
                            Railroad.LOGGER.error(
                                "Failed to attach debugger to {}",
                                configuration.data().getName(),
                                throwable);

                            return null;
                        });
                }

                process.onExit().thenAccept(p -> {
                    runningProcesses.remove(configuration);
                    if (p.exitValue() != 0) {
                        Railroad.LOGGER.error("Application process exited with code: {}", p.exitValue());
                    } else {
                        Railroad.LOGGER.debug("Application process finished successfully.");
                    }
                });

                return null;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to start Jar Application process", exception);
            }
        }));
    }

    private static void compilePlainJavaProject(
        Project project,
        JDK jdk,
        Path workingDirectory,
        String[] classpathEntries
    ) {
        Path sourceRoot = project.getPath().resolve("src/main/java").toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot))
            throw new IllegalStateException("Java source root does not exist: " + sourceRoot);

        List<Path> sources;
        try (var paths = Files.walk(sourceRoot)) {
            sources = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan Java source files", exception);
        }

        if (sources.isEmpty())
            throw new IllegalStateException("No Java source files found under " + sourceRoot);

        Path outputDirectory = resolveClasspathPath(classpathEntries[0], workingDirectory);
        if (Files.exists(outputDirectory) && !Files.isDirectory(outputDirectory))
            throw new IllegalStateException(
                "The first classpath entry must be the compiled output directory: " + outputDirectory);

        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create Java output directory: " + outputDirectory, exception);
        }

        String javacExecutableName = JDKManager.JAVA_EXECUTABLE_NAME.endsWith(".exe")
            ? "javac.exe"
            : "javac";
        Path javacExecutable = jdk.path().resolve("bin").resolve(javacExecutableName);
        if (!Files.isRegularFile(javacExecutable))
            throw new IllegalStateException("Selected JDK does not contain javac: " + javacExecutable);

        List<String> command = new ArrayList<>();
        command.add(javacExecutable.toString());
        command.add("-g");
        command.add("-d");
        command.add(outputDirectory.toString());
        command.add("-classpath");
        command.add(Arrays.stream(classpathEntries)
            .map(entry -> resolveClasspathPath(entry, workingDirectory).toString())
            .reduce((left, right) -> left + File.pathSeparator + right)
            .orElse(outputDirectory.toString()));
        sources.forEach(source -> command.add(source.toString()));

        Railroad.LOGGER.debug("Compiling plain Java project with command: {}", String.join(" ", command));

        try {
            Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();

            var compilerOutput = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!compilerOutput.isEmpty()) {
                        compilerOutput.append(System.lineSeparator());
                    }
                    compilerOutput.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0)
                throw new IllegalStateException("javac exited with code " + exitCode +
                    (compilerOutput.isEmpty() ? "" : System.lineSeparator() + compilerOutput));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Java compilation was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to run javac", exception);
        }
    }

    private static Path resolveClasspathPath(String entry, Path workingDirectory) {
        Path path = Path.of(entry);
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    // TODO: temporary
    private SourceResolver createSourceResolver(Project project) {
        Path root = project.getPath();

        return new SourceResolver(
            List.of(
                root.resolve("src/main/java"),
                root.resolve("src/test/java")),
            DependencySourceIndex.EMPTY);
    }

    // TODO: temporary
    private void handleDebugEvent(DebugSessionEvent event) {
        Railroad.LOGGER.debug("Debugger event: {}", event);

        if (event instanceof DebugSessionEvent.Suspended suspended) {
            JdiDebugSession session = Services.DEBUG_SERVICE
                .getActiveSession()
                .orElseThrow();

            session.threads().thenAccept(threads -> {
                Railroad.LOGGER.debug("Threads: {}", threads);

                long threadId = suspended.threadId();

                session.stackFrames(threadId).thenAccept(frames -> {
                    Railroad.LOGGER.debug(
                        "Frames: {}",
                        frames);

                    if (frames.isEmpty())
                        return;

                    DebugFrame frame = frames.getFirst();

                    session.variables(frame.id())
                        .thenAccept(variables -> Railroad.LOGGER.debug(
                            "Variables: {}",
                            variables));
                });
            });
        }
    }

    private static String[] buildCommand(
        JDK jdk,
        String mainClass,
        String[] classpathEntries,
        String[] programArguments,
        String[] vmOptions,
        boolean debug,
        int debugPort
    ) {
        String javaExecutable = jdk.path().resolve("bin").resolve(JDKManager.JAVA_EXECUTABLE_NAME).toString();
        String[] vm = vmOptions == null ? new String[0] : vmOptions;
        String[] args = programArguments == null ? new String[0] : programArguments;
        String classpath = String.join(File.pathSeparator, classpathEntries != null ? classpathEntries : new String[0]);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);

        if (debug) {
            if (debugPort <= 0)
                throw new IllegalStateException("Debug port must be provided when debug mode is enabled.");

            command.add(
                "-agentlib:jdwp=transport=dt_socket," +
                    "server=y," +
                    "suspend=y," +
                    "address=127.0.0.1:%d".formatted(debugPort));
        }

        command.addAll(List.of(vm));
        if (classpathEntries != null && classpathEntries.length > 0) {
            command.add("-cp");
            command.add(classpath);
        }

        command.add(mainClass);
        command.addAll(List.of(args));
        return command.toArray(new String[0]);
    }

    private static String[] sanitizeClasspathEntries(String[] classpathEntries) {
        if (classpathEntries == null || classpathEntries.length == 0)
            return new String[0];

        return Arrays.stream(classpathEntries)
            .map(entry -> entry == null ? "" : entry.strip())
            .filter(entry -> !entry.isBlank())
            .toArray(String[]::new);
    }

    private static int findFreePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new RuntimeException("Failed to find a free port for debugging", exception);
        }
    }

    private static String normalizeMainClass(String mainClass) {
        if (mainClass == null)
            return null;

        String normalized = mainClass.strip();
        if (normalized.toLowerCase().startsWith("src.main.java.")) {
            normalized = normalized.substring("src.main.java.".length());
        }
        if (normalized.toLowerCase().endsWith(".java")) {
            normalized = normalized.substring(0, normalized.length() - ".java".length());
        }

        return normalized;
    }

    private record ProcessOutputHandler(Process process, String name) implements Runnable {
        @Override
        public void run() {
            // In the future IDE, we will read from process.getInputStream() and process.getErrorStream()
            // and display it in the IDE console.
            // For now, we just print to System.out/err.
            new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[" + name + " OUT] " + line);
                    }
                } catch (IOException exception) {
                    System.err.println("[" + name + " ERR] Error reading stdout: " + exception.getMessage());
                }
            }).start();

            new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println("[" + name + " ERR] " + line);
                    }
                } catch (IOException exception) {
                    System.err.println("[" + name + " ERR] Error reading stderr: " + exception.getMessage());
                }
            }).start();
        }
    }
}
