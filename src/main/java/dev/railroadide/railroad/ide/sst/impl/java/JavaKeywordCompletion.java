package dev.railroadide.railroad.ide.sst.impl.java;

import dev.railroadide.railroad.ide.completion.CompletionItem;
import dev.railroadide.railroad.ide.completion.CompletionResult;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxKind;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxNode;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxToken;
import dev.railroadide.railroad.ide.sst.syntax.api.SyntaxTree;
import dev.railroadide.railroad.utility.NameMatcher;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Supplies keyword candidates for the syntactic position of a Java completion prefix.
 */
public final class JavaKeywordCompletion {
    private static final List<String> DECLARATIONS = List.of(
        "abstract", "class", "enum", "final", "interface", "public", "record", "sealed");
    private static final List<String> MEMBERS = List.of(
        "abstract", "boolean", "byte", "char", "class", "double", "enum", "final", "float", "int", "interface",
        "long", "native", "private", "protected", "public", "record", "short", "static", "synchronized",
        "transient", "void", "volatile");
    private static final List<String> STATEMENTS = List.of(
        "assert", "boolean", "byte", "char", "class", "do", "double", "final", "float", "for", "if", "int",
        "long", "new", "return", "short", "switch", "synchronized", "throw", "try", "var", "while");
    private static final List<String> EXPRESSIONS = List.of("false", "new", "null", "super", "switch", "this", "true");
    private static final Set<SyntaxKind> TYPE_BODIES = Set.of(
        JavaSyntaxKinds.CLASS_BODY, JavaSyntaxKinds.INTERFACE_BODY, JavaSyntaxKinds.ENUM_BODY,
        JavaSyntaxKinds.RECORD_BODY, JavaSyntaxKinds.ANNOTATION_TYPE_BODY, JavaSyntaxKinds.ANONYMOUS_CLASS_BODY);
    private static final Set<SyntaxKind> CONTROL_BODIES = Set.of(
        JavaSyntaxKinds.IF_STATEMENT, JavaSyntaxKinds.WHILE_STATEMENT, JavaSyntaxKinds.DO_WHILE_STATEMENT,
        JavaSyntaxKinds.FOR_STATEMENT, JavaSyntaxKinds.LABELED_STATEMENT);

    private JavaKeywordCompletion() {
    }

    /**
     * Completes a keyword alongside the other names at a typed prefix.
     *
     * @param tree parsed source
     * @param node syntax node at the trigger offset
     * @param triggerAt offset of the triggering character
     * @return keyword suggestions, or {@code null} when there are none
     */
    public static @Nullable CompletionResult complete(SyntaxTree tree, @Nullable SyntaxNode node, int triggerAt) {
        if (!(node instanceof SyntaxToken token) || JavaSemanticAnalyzer.isTriviaToken(token))
            return null;

        String text = tree.documentSnapshot().text();
        int start = token.start();
        String prefix = text.substring(start, triggerAt + 1);
        if (prefix.isEmpty() || !Character.isJavaIdentifierStart(prefix.codePointAt(0))
            || !prefix.codePoints().allMatch(Character::isJavaIdentifierPart) || afterQualifier(text, start))
            return null;

        List<CompletionItem> items = keywordsAt(token).stream()
            .filter(word -> !word.equals(token.text()) && NameMatcher.matches(prefix, word))
            .map(CompletionItem::keyword)
            .toList();
        return items.isEmpty() ? null : new CompletionResult(start, items);
    }

    private static List<String> keywordsAt(SyntaxToken token) {
        SyntaxNode usage = token.parent().orElse(null);
        boolean expression = usage != null && usage.kind().equals(JavaSyntaxKinds.NAME_EXPRESSION);
        if (expression && JavaSemanticAnalyzer.isSelectorNameExpression(usage))
            return List.of();

        for (SyntaxNode child = token,
            parent = usage; parent != null; child = parent, parent = parent.parent().orElse(null)) {
            SyntaxKind kind = parent.kind();
            if (kind.equals(JavaSyntaxKinds.BLOCK))
                return firstCodeToken(child) == token ? STATEMENTS : expression ? EXPRESSIONS : List.of();

            if (kind.equals(JavaSyntaxKinds.COMPILATION_UNIT))
                return firstCodeToken(child) == token ? DECLARATIONS : List.of();

            if (TYPE_BODIES.contains(kind))
                return firstCodeToken(child) == token ? MEMBERS : expression ? EXPRESSIONS : List.of();

            if (CONTROL_BODIES.contains(kind) && child.kind().equals(JavaSyntaxKinds.EXPRESSION_STATEMENT)
                && firstCodeToken(child) == token)
                return STATEMENTS;
        }

        return List.of();
    }

    private static boolean afterQualifier(String text, int start) {
        int previous = start - 1;
        while (previous >= 0 && Character.isWhitespace(text.charAt(previous))) {
            previous--;
        }

        return previous >= 0 && (text.charAt(previous) == '.' || text.charAt(previous) == '@'
            || text.charAt(previous) == ':' && previous > 0 && text.charAt(previous - 1) == ':');
    }

    private static @Nullable SyntaxToken firstCodeToken(SyntaxNode node) {
        if (node.width() == 0)
            return null;

        if (node instanceof SyntaxToken token)
            return JavaSemanticAnalyzer.isTriviaToken(token) ? null : token;

        for (SyntaxNode child : node.children()) {
            SyntaxToken token = firstCodeToken(child);
            if (token != null)
                return token;
        }

        return null;
    }
}
