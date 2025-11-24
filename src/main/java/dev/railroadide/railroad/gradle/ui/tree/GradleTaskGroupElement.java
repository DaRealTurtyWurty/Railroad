package dev.railroadide.railroad.gradle.ui.tree;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

public class GradleTaskGroupElement extends GradleTreeElement {
    public GradleTaskGroupElement(String name) {
        super("<no-group>".equals(name) ? "Other" : name);
    }

    @Override
    public Ikon getIcon() {
        return FontAwesomeSolid.FOLDER;
    }

    @Override
    public String getStyleClass() {
        return "gradle-tasks-group-element";
    }
}
