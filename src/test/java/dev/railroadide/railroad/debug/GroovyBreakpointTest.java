package dev.railroadide.railroad.debug;

import dev.railroadide.railroad.debug.breakpoint.BreakpointService;
import dev.railroadide.railroad.debug.jdi.JdiDebugSession;
import dev.railroadide.railroad.debug.model.DebugEndpoint;
import dev.railroadide.railroad.debug.model.DebugFrame;
import dev.railroadide.railroad.debug.model.DebugSessionEvent;
import dev.railroadide.railroad.debug.model.DebugStopReason;
import dev.railroadide.railroad.debug.source.DebugSource;
import dev.railroadide.railroad.debug.source.DependencySourceIndex;
import dev.railroadide.railroad.debug.source.ProjectSourceResolver;
import dev.railroadide.railroad.debug.source.SourceResolver;
import groovy.lang.GroovySystem;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class GroovyBreakpointTest {
    private static final String TARGET = """
        package demo

        class Target {
            static void main(String[] args) {
                System.in.read()
                def doubled = [1, 2].collect { value ->
                    value * 2
                }
                println "hit $doubled"
            }
        }
        """;
    private static final int CLOSURE_LINE = 7;
    private static final int METHOD_LINE = 9;

    @TempDir
    private Path directory;

    @Test
    public void breakpointsStopInGroovyMethodsAndClosures() throws Exception {
        Path sourceRoot = Files.createDirectories(directory.resolve("src/main/groovy"));
        Path source = Files.writeString(Files.createDirectories(sourceRoot.resolve("demo")).resolve("Target.groovy"),
            TARGET);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var configuration = new CompilerConfiguration();
        configuration.setTargetDirectory(classes.toFile());
        var unit = new CompilationUnit(configuration);
        unit.addSource(source.toFile());
        unit.compile();

        String groovy = Path.of(GroovySystem.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
            "-cp", classes + File.pathSeparator + groovy, "demo.Target")
            .redirectErrorStream(true).start();
        BlockingQueue<String> output = new LinkedBlockingQueue<>();
        Thread.ofPlatform().daemon().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(output::add);
            } catch (Exception exception) {
                output.add(exception.toString());
            }
        });

        var debug = new DebugService();
        try {
            String address = awaitOutput(output, "Listening for transport dt_socket at address:");
            int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1).trim());
            var breakpoints = new BreakpointService();
            breakpoints.add(source, CLOSURE_LINE);
            breakpoints.add(source, METHOD_LINE);
            BlockingQueue<DebugSessionEvent.Suspended> stops = new LinkedBlockingQueue<>();
            SourceResolver resolver = ProjectSourceResolver.create(directory, DependencySourceIndex.EMPTY);
            JdiDebugSession session = debug.startSession(new DebugEndpoint("127.0.0.1", port), resolver,
                breakpoints, _ -> true, event -> {
                    if (event instanceof DebugSessionEvent.Suspended suspended) {
                        stops.add(suspended);
                    }
                }).get(10, TimeUnit.SECONDS);

            process.getOutputStream().write('x');
            process.getOutputStream().flush();
            for (int expectedLine : new int[]{CLOSURE_LINE, CLOSURE_LINE, METHOD_LINE}) {
                DebugSessionEvent.Suspended stop = stops.poll(10, TimeUnit.SECONDS);
                assertNotNull(stop, "The debuggee did not stop at line " + expectedLine);
                assertEquals(DebugStopReason.BREAKPOINT, stop.reason());
                DebugFrame frame = session.stackFrames(stop.threadId()).get(10, TimeUnit.SECONDS).getFirst();
                assertEquals(expectedLine, frame.line());
                assertTrue(frame.source().matches(new DebugSource.FileSource(source)),
                    "The stop should map back to the Groovy source: " + frame.source());
                session.resume().get(10, TimeUnit.SECONDS);
            }

            awaitOutput(output, "hit [2, 4]");
        } finally {
            try {
                debug.terminateActiveSession().get(10, TimeUnit.SECONDS);
            } finally {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static String awaitOutput(BlockingQueue<String> output, String prefix) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(line, "Missing debuggee output: " + prefix);
            if (line.startsWith(prefix))
                return line;
        }

        throw new AssertionError("Missing debuggee output: " + prefix);
    }
}
