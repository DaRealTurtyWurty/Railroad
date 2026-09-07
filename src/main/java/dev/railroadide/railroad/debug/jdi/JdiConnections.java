package dev.railroadide.railroad.debug.jdi;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class JdiConnections {
    private static final long ATTACH_RETRY_TIMEOUT_MILLIS = 5_000;
    private static final long ATTACH_RETRY_DELAY_MILLIS = 50;

    private JdiConnections() {
    }

    public static VirtualMachine attach(String host, int port) throws IllegalConnectorArgumentsException, IOException {
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException(String.format("Invalid port number: %d", port));

        AttachingConnector connector = Bootstrap.virtualMachineManager()
            .attachingConnectors()
            .stream()
            .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Could not find attaching connector"));

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(host);
        arguments.get("port").setValue(Integer.toString(port));
        arguments.get("timeout").setValue(Long.toString(5000));

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ATTACH_RETRY_TIMEOUT_MILLIS);
        while (true) {
            try {
                return connector.attach(arguments);
            } catch (ConnectException exception) {
                if (System.nanoTime() >= deadline)
                    throw exception;

                try {
                    Thread.sleep(ATTACH_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for the debug target", interruptedException);
                }
            }
        }
    }
}
