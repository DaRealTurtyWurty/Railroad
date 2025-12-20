package dev.railroadide.railroad.gradle.ui.tree;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;

@Getter
public abstract class GradleTreeElement {
    private final String name;

    public GradleTreeElement(String name) {
        this.name = name;
    }

    public abstract Ikon getIcon();

    public abstract String getStyleClass();

    public Tooltip getTooltip() {
        return null;
    }

    public ContextMenu getContextMenu() {
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
