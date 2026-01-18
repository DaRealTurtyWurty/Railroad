package dev.railroadide.railroad.ide.console;

import java.util.Objects;
import java.util.function.Consumer;

public final class ConsoleInputBinding implements AutoCloseable {
    private final ConsoleService consoleService;
    private final Consumer<String> boundConsumer;
    private final Consumer<String> previousConsumer;

    private ConsoleInputBinding(ConsoleService consoleService,
                                Consumer<String> boundConsumer,
                                Consumer<String> previousConsumer) {
        this.consoleService = consoleService;
        this.boundConsumer = boundConsumer;
        this.previousConsumer = previousConsumer;
    }

    public static ConsoleInputBinding bind(Consumer<String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        ConsoleService consoleService = ConsoleService.getInstance();
        Consumer<String> previous = consoleService.stdinConsumerProperty().get();
        consoleService.setStdinConsumer(consumer);
        return new ConsoleInputBinding(consoleService, consumer, previous);
    }

    @Override
    public void close() {
        if (consoleService.stdinConsumerProperty().get() == boundConsumer) {
            consoleService.setStdinConsumer(previousConsumer);
        }
    }
}
