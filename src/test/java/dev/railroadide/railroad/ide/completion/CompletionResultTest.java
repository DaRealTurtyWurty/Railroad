package dev.railroadide.railroad.ide.completion;

import dev.railroadide.railroad.ide.signature.SignatureHelp;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CompletionResultTest {
    @Test
    public void completionCandidatesAndSignatureParametersAreImmutableSnapshots() {
        var item = new CompletionItem("value", "value");
        List<CompletionItem> items = new ArrayList<>(List.of(item));
        var completion = new CompletionResult(0, items);
        items.clear();
        assertEquals(List.of(item), completion.items());
        assertThrows(UnsupportedOperationException.class, () -> completion.items().clear());
        assertThrows(IllegalArgumentException.class, () -> new CompletionResult(-1, List.of(item)));

        var parameter = new SignatureHelp.ParameterInfo("text", "value", false);
        List<SignatureHelp.ParameterInfo> parameters = new ArrayList<>(List.of(parameter));
        var signature = new SignatureHelp("", "", "print", parameters, 0, false, "unit", false);
        parameters.clear();
        assertEquals(List.of(parameter), signature.parameters());
        assertThrows(UnsupportedOperationException.class, () -> signature.parameters().clear());
    }

    @Test
    public void genericProvidersCanTriggerPrefixCompletionWithoutJavaPunctuation() {
        CompletionProvider provider = (_, _) -> CompletionResult.empty();
        assertTrue(provider.shouldTrigger("", "name"));
        assertFalse(provider.shouldTrigger("", ""));
    }

    @Test
    public void javaCompletionTriggersOnDotsAndOnTheFirstCharacterOfANewIdentifier() {
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(),
            new Class<?>[]{Project.class},
            (_, _, _) -> null);
        var provider = new JavaCompletionProvider(project, null, _ -> null);
        assertTrue(provider.shouldTrigger("value", "."));
        assertTrue(provider.shouldTrigger("    ", "t"));
        assertTrue(provider.shouldTrigger("", "t"));
        assertTrue(provider.shouldTrigger("class A {", "_"));
        assertFalse(provider.shouldTrigger("    t", "o"));
        assertFalse(provider.shouldTrigger("value.", "l"));
        assertFalse(provider.shouldTrigger("    @", "O"));
        assertFalse(provider.shouldTrigger("    ", "1"));
        assertFalse(provider.shouldTrigger("    ", "to"));
        assertFalse(provider.shouldTrigger("value", ":"));
    }
}
