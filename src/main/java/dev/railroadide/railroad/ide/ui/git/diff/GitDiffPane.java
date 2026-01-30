package dev.railroadide.railroad.ide.ui.git.diff;

import dev.railroadide.core.ui.RRBorderPane;
import dev.railroadide.railroad.ide.ui.TextEditorPane;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.nio.file.Path;

public class GitDiffPane extends RRBorderPane {
    private final ObjectProperty<Path> filePath = new SimpleObjectProperty<>();

    private TextEditorPane editor;

    public GitDiffPane(Path filePath) {
        this.filePath.set(filePath);

        if (this.filePath.get() != null) {
            editor = new TextEditorPane(this.filePath.get());
            setCenter(editor);
        }

        this.filePath.addListener((obs, oldPath, newPath) -> {
            if (newPath != null) {
                if (editor == null) {
                    editor = new TextEditorPane(newPath);
                    setCenter(editor);
                } else {
                    editor.close();
                }
            } else {
                if (editor != null) {
                    editor.close();
                    editor = null;
                }

                setCenter(null);
            }
        });
    }

    public GitDiffPane() {
        this(null);
    }

    public ObjectProperty<Path> filePathProperty() {
        return filePath;
    }

    public Path getFilePath() {
        return filePath.get();
    }

    public void setFilePath(Path filePath) {
        this.filePath.set(filePath);
    }
}
