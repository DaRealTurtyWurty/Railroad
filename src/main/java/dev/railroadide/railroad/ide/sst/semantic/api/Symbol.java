package dev.railroadide.railroad.ide.sst.semantic.api;

import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;

import java.util.Optional;

/**
 * Public semantic symbol contract.
 * <p>
 * A symbol represents a language-defined entity that can be declared or referenced.
 * Source syntax is optional, so binary and synthetic symbols need no placeholder tree.
 * Implementations supplied to published semantic models or scopes must be deeply
 * immutable and safe for concurrent reads, including any attached syntax node.
 * Models retain symbol identity; they do not clone provider-defined implementations.
 */
public interface Symbol {
    /**
     * Returns the broad symbol category.
     *
     * @return the symbol kind
     */
    SymbolKind kind();

    /**
     * Returns the simple source-level name of the symbol.
     *
     * @return the simple symbol name
     */
    String simpleName();

    /**
     * Returns the fully qualified name when one exists.
     *
     * @return the qualified name, if available
     */
    Optional<String> qualifiedName();

    /**
     * Returns the syntax node that declared this symbol when it came from source.
     *
     * @return the declaration node, if this symbol came from source
     */
    Optional<SyntaxNode> declaration();
}
