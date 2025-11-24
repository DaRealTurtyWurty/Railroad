package dev.railroadide.railroad.gradle.ui.tree;

import dev.railroadide.locatedependencies.DependencyNode;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

@Getter
public class GradleDependencyElement extends GradleTreeElement {
    private final DependencyNode dependencyNode;

    public GradleDependencyElement(DependencyNode dependencyNode) {
        super(dependencyNode.group() + ":" + dependencyNode.name() + ":" + dependencyNode.version());
        this.dependencyNode = dependencyNode;
    }

    @Override
    public Ikon getIcon() {
        return FontAwesomeSolid.BOOK;
    }

    @Override
    public String getStyleClass() {
        return "gradle-dependency-element";
    }

}
