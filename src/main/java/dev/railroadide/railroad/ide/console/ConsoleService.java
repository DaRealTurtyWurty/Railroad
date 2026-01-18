package dev.railroadide.railroad.ide.console;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ConsoleService {
    private static final int DEFAULT_MAX_LINES = 10000;
    private static final ConsoleService INSTANCE = new ConsoleService();

    private final Object lock = new Object();
    private final ConsoleModel model = new ConsoleModel(DEFAULT_MAX_LINES);
    private final List<Consumer<ConsoleDelta>> listeners = new CopyOnWriteArrayList<>();
    private final ObjectProperty<Consumer<String>> stdinConsumer = new SimpleObjectProperty<>();

    private ConsoleService() {
    }

    public static ConsoleService getInstance() {
        return INSTANCE;
    }

    public void addListener(Consumer<ConsoleDelta> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Consumer<ConsoleDelta> listener) {
        listeners.remove(listener);
    }

    public ConsoleDelta write(CharSequence chunk, ConsoleStream stream) {
        ConsoleDelta delta;
        synchronized (lock) {
            delta = model.write(chunk, stream);
        }
        notifyListeners(delta);
        return delta;
    }

    public void clear() {
        synchronized (lock) {
            model.clear();
        }
        notifyListeners(new ConsoleDelta.ResetAll());
    }

    public int getLineCount() {
        synchronized (lock) {
            return model.createLinesSnapshot().size();
        }
    }

    public ConsoleLine getLineSnapshot(int index) {
        synchronized (lock) {
            return model.createLinesSnapshot().get(index);
        }
    }

    public List<ConsoleLine> createLinesSnapshot() {
        synchronized (lock) {
            return model.createLinesSnapshot();
        }
    }

    public void submitStdin(String input) {
        Consumer<String> consumer = stdinConsumer.get();
        if (consumer != null) {
            consumer.accept(input);
        }
    }

    public void setStdinConsumer(Consumer<String> consumer) {
        stdinConsumer.set(consumer);
    }

    public ReadOnlyObjectProperty<Consumer<String>> stdinConsumerProperty() {
        return stdinConsumer;
    }

    private void notifyListeners(ConsoleDelta delta) {
        if (delta instanceof ConsoleDelta.None)
            return;
        
        for (Consumer<ConsoleDelta> listener : listeners) {
            listener.accept(delta);
        }
    }
}
