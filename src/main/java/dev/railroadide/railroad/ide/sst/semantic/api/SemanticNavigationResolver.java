package dev.railroadide.railroad.ide.sst.semantic.api;

import dev.railroadide.railroad.ide.sst.document.api.Location;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;

import java.util.Optional;

public final class SemanticNavigationResolver {
    private SemanticNavigationResolver() {
    }

    public static Optional<Location.TextLocation> definitionAt(SemanticModel model, int offset) {
        return symbolAt(model, offset)
            .flatMap(symbol -> declarationLocation(model, symbol));
    }

    public static Optional<Symbol> symbolAt(SemanticModel model, int offset) {
        SyntaxNode root = model.syntaxTree().root();
        if (offset < root.start() || offset >= root.end())
            return Optional.empty();

        SyntaxNode node = findNodeAt(root, offset);
        return model.resolvedSymbol(node);
    }

    public static Optional<Location.TextLocation> declarationLocation(SemanticModel model, Symbol symbol) {
        return symbol.declaration()
            .filter(node -> belongsToTree(node, model.syntaxTree().root()))
            .map(node -> Location.TextLocation.from(
                model.syntaxTree().documentSnapshot(),
                node.start(),
                node.end()));
    }

    private static boolean belongsToTree(SyntaxNode node, SyntaxNode root) {
        SyntaxNode current = node;

        while (current != null) {
            if (current == root)
                return true;

            current = current.parent().orElse(null);
        }

        return false;
    }

    private static SyntaxNode findNodeAt(SyntaxNode node, int offset) {
        for (SyntaxNode child : node.children()) {
            if (offset >= child.start() && offset < child.end())
                return findNodeAt(child, offset);
        }

        return node;
    }
}
