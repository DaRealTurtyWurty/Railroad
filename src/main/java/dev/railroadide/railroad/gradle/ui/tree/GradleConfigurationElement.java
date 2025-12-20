package dev.railroadide.railroad.gradle.ui.tree;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

public class GradleConfigurationElement extends GradleTreeElement {
    public GradleConfigurationElement(String name) {
        super(name);
    }

    @Override
    public Ikon getIcon() {
        return FontAwesomeSolid.FOLDER;
    }

    @Override
    public String getStyleClass() {
        return "gradle-configuration-element";
    }
}
