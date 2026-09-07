package dev.railroadide.railroad.debug;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;
import java.util.Map;

public final class JdiConnections {
    private JdiConnections() {
    }

    public static VirtualMachine attach(int port) throws IllegalConnectorArgumentsException, IOException {
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException(String.format("Invalid port number: %d", port));

        AttachingConnector connector = Bootstrap.virtualMachineManager()
            .attachingConnectors()
            .stream()
            .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Could not find attaching connector"));

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(Integer.toString(port));
        arguments.get("timeout").setValue(Long.toString(5000));

        return connector.attach(arguments);
    }
}
