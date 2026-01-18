package dev.railroadide.railroad.ide.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class ConsoleProcessBridge implements AutoCloseable {
    private final ConsoleInputBinding inputBinding;

    private ConsoleProcessBridge(ConsoleInputBinding inputBinding) {
        this.inputBinding = inputBinding;
    }

    public static ConsoleProcessBridge attach(Process process, String name) {
        ConsoleService consoleService = ConsoleService.getInstance();
        startStreamThread(process.getInputStream(), name, ConsoleStream.STDOUT, "OUT", consoleService);
        startStreamThread(process.getErrorStream(), name, ConsoleStream.STDERR, "ERR", consoleService);

        OutputStream stdin = process.getOutputStream();
        ConsoleInputBinding inputBinding = ConsoleInputBinding.bind(input -> {
            try {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException exception) {
                consoleService.write(
                    "[" + name + " ERR] Failed to write to stdin: " + exception.getMessage() + System.lineSeparator(),
                    ConsoleStream.STDERR
                );
            }
        });

        return new ConsoleProcessBridge(inputBinding);
    }

    @Override
    public void close() {
        inputBinding.close();
    }

    private static void startStreamThread(InputStream stream,
                                          String name,
                                          ConsoleStream targetStream,
                                          String label,
                                          ConsoleService consoleService) {
        String threadName = name + "-" + targetStream.name().toLowerCase() + "-console";
        new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    consoleService.write(
                        "[" + name + " " + label + "] " + line + System.lineSeparator(),
                        targetStream
                    );
                }
            } catch (IOException exception) {
                consoleService.write(
                    "[" + name + " ERR] Error reading " + label.toLowerCase() + ": " + exception.getMessage()
                        + System.lineSeparator(),
                    ConsoleStream.STDERR
                );
            }
        }, threadName).start();
    }
}
