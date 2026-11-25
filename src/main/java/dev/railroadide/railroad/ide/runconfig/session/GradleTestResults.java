package dev.railroadide.railroad.ide.runconfig.session;

import dev.railroadide.railroad.gradle.model.GradleBuildModel;
import dev.railroadide.railroad.ide.runconfig.defaults.data.GradleRunConfigurationData;
import dev.railroadide.railroadplugin.dto.RailroadContentRoot;
import dev.railroadide.railroadplugin.dto.RailroadModule;
import org.gradle.tooling.Failure;
import org.gradle.tooling.TestAssertionFailure;
import org.gradle.tooling.TestFailure;
import org.gradle.tooling.events.OperationDescriptor;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.events.task.TaskFailureResult;
import org.gradle.tooling.events.task.TaskFinishEvent;
import org.gradle.tooling.events.task.TaskOperationDescriptor;
import org.gradle.tooling.events.test.*;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

/**
 * The outcomes reported by Gradle for one run, including task-qualified filters for rerunning failed methods.
 */
public final class GradleTestResults {
    private static final Pattern FRAME = Pattern.compile(
        "(?m)^\\s*at (?:[^\\s/]+/)?([\\w.$]+)\\.[^.(]+\\(([\\w$.-]+\\.java):(\\d{1,9})\\)",
        Pattern.UNICODE_CHARACTER_CLASS);

    private int passed;
    private int skipped;
    private final List<FailedTest> failures = new ArrayList<>();

    private final Set<String> failedTasks = new HashSet<>();

    public synchronized void record(ProgressEvent event) {
        if (event instanceof TaskFinishEvent task && task.getResult() instanceof TaskFailureResult) {
            failedTasks.add(task.getDescriptor().getTaskPath());
        }

        if (!(event instanceof TestFinishEvent finished)
            || !(finished.getDescriptor() instanceof JvmTestOperationDescriptor test)
            || test.getJvmTestKind() != JvmTestKind.ATOMIC)
            return;

        switch (finished.getResult()) {
            case TestFailureResult result -> {
                List<String> details = new ArrayList<>();
                for (Failure failure : result.getFailures()) {
                    describe(failure, details);
                }

                String label = test.getClassName() == null
                    ? test.getTestDisplayName()
                    : test.getClassName() + " · " + test.getTestDisplayName();
                String method = test.getMethodName();
                String selector = null;
                if (test.getClassName() != null && method != null) {
                    // Gradle reports Jupiter signatures and invocation suffixes, but --tests selects Java methods.
                    String name = method.split("[\\(\\[]", 2)[0];
                    if (SourceVersion.isIdentifier(name)) {
                        selector = test.getClassName() + "." + name;
                    }
                }

                failures.add(new FailedTest(selector, label, String.join("\n", details), taskPath(test)));
            }

            case TestSkippedResult _ -> skipped++;
            case TestSuccessResult _ -> passed++;
            default -> {
            }
        }
    }

    private static String taskPath(OperationDescriptor descriptor) {
        for (OperationDescriptor parent = descriptor.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof TaskOperationDescriptor task)
                return task.getTaskPath();
        }

        return "";
    }

    private static void describe(Failure failure, List<String> details) {
        if (failure.getMessage() != null) {
            details.add(failure.getMessage());
        }

        if (failure instanceof TestAssertionFailure assertion && assertion.getExpected() != null
            && assertion.getActual() != null) {
            details.add("Expected: " + assertion.getExpected() + "\nActual: " + assertion.getActual());
        }

        String trace = failure instanceof TestFailure test ? test.getStacktrace() : failure.getDescription();
        if (trace != null) {
            details.add(trace);
        }

        failure.getCauses().forEach(cause -> describe(cause, details));
    }

    /**
     * Whether the test results account for every failed task in this build.
     */
    public synchronized boolean hasOnlyTestFailures() {
        return !failedTasks.isEmpty() && failedTasks.stream()
            .allMatch(task -> failures.stream().anyMatch(failure -> failure.taskPath().equals(task)));
    }

    public synchronized int passed() {
        return passed;
    }

    public synchronized int skipped() {
        return skipped;
    }

    public synchronized List<FailedTest> failures() {
        return List.copyOf(failures);
    }

    /**
     * CLI test filters are intersected with the test task's own includes and scoped to their task.
     */
    public synchronized List<String> failedTestArguments() {
        Map<String, Set<String>> tasks = new LinkedHashMap<>();
        for (FailedTest failure : failures) {
            if (failure.selector() == null || failure.taskPath().isEmpty())
                return List.of();

            tasks.computeIfAbsent(failure.taskPath(), _ -> new LinkedHashSet<>()).add(failure.selector());
        }

        List<String> arguments = new ArrayList<>();
        tasks.forEach((task, selectors) -> {
            arguments.add(task);
            arguments.add("--rerun");
            for (String selector : selectors) {
                arguments.add("--tests");
                arguments.add(selector);
            }
        });

        return List.copyOf(arguments);
    }

    public record SourceLine(Path file, int line) {
    }

    public record FailedTest(@Nullable String selector, String label, String details, String taskPath) {
        /**
         * Finds the first project frame, using the owning task's module to disambiguate identical class names.
         */
        public Optional<SourceLine> source(GradleRunConfigurationData data, @Nullable GradleBuildModel model) {
            Path root = data.getGradleProjectPath();
            String module = taskPath.contains(":") ? taskPath.substring(0, taskPath.lastIndexOf(':')) : "";
            String task = taskPath.substring(taskPath.lastIndexOf(':') + 1);
            Set<Path> roots = new LinkedHashSet<>();
            if (model != null && model.project() != null) {
                for (RailroadModule candidate : model.project().getModules()) {
                    if (candidate.getPath().equals(module.isEmpty() ? ":" : module)) {
                        root = candidate.getProjectDir();
                        for (RailroadContentRoot content : candidate.getContentRoots()) {
                            content.getTestSourceDirectories().forEach(dir -> roots.add(dir.getDirectory().toPath()));
                            content.getSourceDirectories().forEach(dir -> roots.add(dir.getDirectory().toPath()));
                        }

                        break;
                    }
                }
            } else if (!module.isEmpty() && !root.endsWith(module.substring(1).replace(':', '/'))) {
                root = root.resolve(module.substring(1).replace(':', '/'));
            }

            if (!task.isEmpty()) {
                roots.add(root.resolve("src").resolve(task).resolve("java"));
            }

            roots.add(root.resolve("src/test/java"));
            roots.add(root.resolve("src/main/java"));
            Matcher frame = FRAME.matcher(details);
            while (frame.find()) {
                String type = frame.group(1);
                String filename = frame.group(2);
                int line = Integer.parseInt(frame.group(3));
                if (data.getMainSource() != null && (type.equals(data.getMainClass())
                    || type.startsWith(data.getMainClass() + "$"))) {
                    Path file = data.getGradleProjectPath().resolve(data.getMainSource());
                    if (file.endsWith(filename) && Files.isRegularFile(file))
                        return Optional.of(new SourceLine(file, line));
                }

                String relative = type.substring(0, type.lastIndexOf('.') + 1).replace('.', '/') + filename;
                for (Path sourceRoot : roots) {
                    Path file = sourceRoot.resolve(relative);
                    if (Files.isRegularFile(file))
                        return Optional.of(new SourceLine(file, line));
                }
            }

            return Optional.empty();
        }
    }
}
