package dev.railroadide.railroad.ide.language.impl.groovy;

import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.ui.codeeditor.BreakpointGutterBinding;
import dev.railroadide.railroad.ide.ui.codeeditor.CodeEditorPane;
import dev.railroadide.railroad.ide.ui.codeeditor.EditorFeatures;

/**
 * Groovy editor behaviour: a breakpoint gutter, since Groovy compiles to JVM classes the debugger can stop in.
 */
public final class GroovyEditorFeatures implements EditorFeatures {
    @Override
    public AutoCloseable install(CodeEditorPane editor) {
        return new BreakpointGutterBinding(editor.getGutter(), editor, editor::getFilePath,
            Services.BREAKPOINT_SERVICE);
    }
}
