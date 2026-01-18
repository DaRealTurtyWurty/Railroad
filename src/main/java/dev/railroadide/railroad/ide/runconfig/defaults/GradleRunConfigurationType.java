package dev.railroadide.railroad.ide.runconfig.defaults;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.gradle.GradleOutputStream;
import dev.railroadide.railroad.ide.console.ConsoleInputBinding;
import dev.railroadide.railroad.ide.console.ConsoleService;
import dev.railroadide.railroad.ide.console.ConsoleStream;
import dev.railroadide.railroad.ide.runconfig.RunConfiguration;
import dev.railroadide.railroad.ide.runconfig.RunConfigurationType;
import dev.railroadide.railroad.ide.runconfig.defaults.data.GradleRunConfigurationData;
import dev.railroadide.railroad.java.JDK;
import dev.railroadide.railroad.java.JDKManager;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.utility.icon.RailroadBrandsIcon;
import javafx.scene.paint.Color;
import org.gradle.tooling.*;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GradleRunConfigurationType extends RunConfigurationType<GradleRunConfigurationData> {
    private final Map<RunConfiguration<GradleRunConfigurationData>, GradleExecutionHandle> executions =
        new ConcurrentHashMap<>();
    private final ExecutorService handleCloser = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "gradle-handle-closer");
        thread.setDaemon(true);
        return thread;
    });

    public GradleRunConfigurationType() {
        super("railroad.runconfig.gradle", RailroadBrandsIcon.GRADLE, Color.web("#6dc24f"));
    }

    @Override
    public CompletableFuture<Void> run(Project project, RunConfiguration<GradleRunConfigurationData> configuration) {
        var result = new CompletableFuture<Void>();
        CompletableFuture.runAsync(() -> {
            try {
                executeGradleBuild(configuration, result);
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<Void> debug(Project project, RunConfiguration<GradleRunConfigurationData> configuration) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
            "Debugging Gradle run configurations is not supported yet."));
    }

    @Override
    public CompletableFuture<Void> stop(Project project, RunConfiguration<GradleRunConfigurationData> configuration) {
        closeHandle(executions.remove(configuration));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isDebuggingSupported(Project project, RunConfiguration<GradleRunConfigurationData> configuration) {
        return false; // TODO: Implement debugging support (?)
    }

    @Override
    public GradleRunConfigurationData createDataInstance(Project project) {
        var data = new GradleRunConfigurationData();
        data.setName("New Gradle Configuration");
        data.setGradleProjectPath(project.getPath());
        data.setJavaHome(/*project.getJDKManager().getDefaultJDK()*/ JDKManager.getDefaultJDK()); // TODO
        return data;
    }

    @Override
    public Class<GradleRunConfigurationData> getDataClass() {
        return GradleRunConfigurationData.class;
    }

    public record GradleExecutionHandle(ProjectConnection connection, CancellationTokenSource cancellationTokenSource)
        implements AutoCloseable {
        @Override
        public void close() {
            cancellationTokenSource.cancel();
            connection.close();
        }
    }

    private static String requireTask(GradleRunConfigurationData data) {
        String task = data.getTask();
        if (task == null || task.isBlank())
            throw new IllegalStateException("Gradle task must be specified.");

        return task;
    }

    private static Path requireGradleProjectPath(GradleRunConfigurationData data) {
        Path path = data.getGradleProjectPath();
        if (path == null)
            throw new IllegalStateException("Gradle project path must be specified.");

        if (Files.notExists(path) || !Files.isDirectory(path))
            throw new IllegalStateException("Gradle project path does not exist or is not a directory: " + path);

        return path;
    }

    private static JDK requireJavaHome(GradleRunConfigurationData data) {
        JDK javaHome = data.getJavaHome();
        if (javaHome == null)
            throw new IllegalStateException("Java home must be specified for Gradle run configurations.");

        return javaHome;
    }

    private void closeHandle(GradleExecutionHandle handle) {
        if (handle == null)
            return;

        handleCloser.execute(() -> {
            try {
                handle.close();
            } catch (Exception exception) {
                Railroad.LOGGER.error("Failed to close Gradle connection", exception);
            }
        });
    }

    private void executeGradleBuild(RunConfiguration<GradleRunConfigurationData> configuration,
                                    CompletableFuture<Void> future) {
        GradleRunConfigurationData data = configuration.data();
        String task = requireTask(data);
        Path gradleProjectPath = requireGradleProjectPath(data);
        Map<String, String> environmentVariables = new HashMap<>(System.getenv());
        if (data.getEnvironmentVariables() != null)
            environmentVariables.putAll(data.getEnvironmentVariables());
        String[] vmOptions = data.getVmOptions() == null ? new String[0] : data.getVmOptions();
        JDK javaHome = requireJavaHome(data);

        ConsoleService consoleService = ConsoleService.getInstance();
        PipedOutputStream stdinWriter = new PipedOutputStream();
        ConsoleInputBinding stdinBinding;
        PipedInputStream stdinReader;
        try {
            stdinReader = new PipedInputStream(stdinWriter);
        } catch (IOException exception) {
            future.completeExceptionally(exception);
            return;
        }

        stdinBinding = ConsoleInputBinding.bind(input -> {
            try {
                stdinWriter.write(input.getBytes(StandardCharsets.UTF_8));
                stdinWriter.flush();
            } catch (IOException exception) {
                consoleService.write(
                    "[Gradle ERR] Failed to write to stdin: " + exception.getMessage() + System.lineSeparator(),
                    ConsoleStream.STDERR
                );
            }
        });

        var connector = GradleConnector.newConnector()
            .forProjectDirectory(gradleProjectPath.toFile())
            .useBuildDistribution();

        try {
            ProjectConnection connection = connector.connect();
            CancellationTokenSource tokenSource = GradleConnector.newCancellationTokenSource();
            var handle = new GradleExecutionHandle(connection, tokenSource);
            executions.put(configuration, handle);

            connection.newBuild()
                .forTasks(task)
                .withArguments("--console=plain")
                .setJvmArguments(vmOptions)
                .setEnvironmentVariables(environmentVariables)
                .setJavaHome(javaHome.path().toFile())
                .setColorOutput(true)
                .withCancellationToken(tokenSource.token())
                .setStandardOutput(new GradleOutputStream(output ->
                    consoleService.write(output, ConsoleStream.STDOUT)
                ))
                .setStandardError(new GradleOutputStream(error ->
                    consoleService.write(error, ConsoleStream.STDERR)
                ))
                .setStandardInput(stdinReader)
                .run(new ResultHandler<>() {
                    @Override
                    public void onComplete(Void result) {
                        closeHandle(executions.remove(configuration));
                        closeConsoleInput(stdinBinding, stdinReader, stdinWriter);
                        future.complete(null);
                    }

                    @Override
                    public void onFailure(GradleConnectionException failure) {
                        Railroad.LOGGER.error("Gradle build failed", failure);
                        closeHandle(executions.remove(configuration));
                        closeConsoleInput(stdinBinding, stdinReader, stdinWriter);
                        future.completeExceptionally(failure);
                    }
                });
        } catch (BuildException exception) {
            closeHandle(executions.remove(configuration));
            closeConsoleInput(stdinBinding, stdinReader, stdinWriter);
            future.completeExceptionally(new RuntimeException("Gradle build failed: " + exception.getMessage(), exception));
        } catch (GradleConnectionException exception) {
            closeConsoleInput(stdinBinding, stdinReader, stdinWriter);
            future.completeExceptionally(exception);
        } catch (Throwable throwable) {
            closeHandle(executions.remove(configuration));
            closeConsoleInput(stdinBinding, stdinReader, stdinWriter);
            future.completeExceptionally(throwable);
        }
    }

    private static void closeConsoleInput(ConsoleInputBinding binding,
                                          PipedInputStream reader,
                                          PipedOutputStream writer) {
        binding.close();
        try {
            reader.close();
        } catch (IOException ignored) {
        }
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }
}
