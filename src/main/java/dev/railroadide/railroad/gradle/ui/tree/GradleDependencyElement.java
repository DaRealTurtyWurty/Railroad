package dev.railroadide.railroad.gradle.ui.tree;

import dev.railroadide.railroadplugin.dto.RailroadDependency;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

@Getter
public class GradleDependencyElement extends GradleTreeElement {
    private final RailroadDependency dependencyNode;

    public GradleDependencyElement(RailroadDependency dependencyNode) {
        super(dependencyNode.getGroup() + ":" + dependencyNode.getName() + ":" + dependencyNode.getVersion());
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
