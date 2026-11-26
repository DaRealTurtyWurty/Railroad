package dev.railroadide.railroad.ide.language.jvm;

import dev.railroadide.railroad.ide.language.LanguageCapabilities;
import dev.railroadide.railroad.ide.language.LanguageRuntime;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import dev.railroadide.railroad.plugin.spi.dto.Project;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Session service through which a JVM language makes the classes its sources declare visible to other JVM languages,
 * for example so that Java code can use classes written in Groovy.
 * <p>
 * Calls run on analysis workers. While the inputs are unchanged, implementations return the same list instance, so
 * callers may cache what they derive from it by identity.
 */
public interface JvmDeclarationProvider {
    /**
     * Describes the classes declared by this language's sources in a captured workspace.
     *
     * @param workspace captured workspace whose documents and configuration are read
     * @param sourceRoots source roots visible to the requesting file; an empty set means every source root
     * @param activityCheck callback that throws once the request is cancelled or expires
     * @return immutable class declarations; the first declaration of a class name wins
     */
    List<JvmClassDeclaration> declarations(
        WorkspaceSnapshot workspace,
        Set<Path> sourceRoots,
        Runnable activityCheck
    );

    /**
     * Collects the declaration providers of every language registered in the shared runtime, opening their project
     * sessions as needed.
     *
     * @param project project whose language sessions supply the providers
     * @return providers in language identifier order
     */
    static List<JvmDeclarationProvider> of(Project project) {
        LanguageRuntime runtime = LanguageSupportRegistry.runtime();
        return runtime.all().stream()
            .filter(contribution -> contribution.supportedCapabilities()
                .contains(LanguageCapabilities.JVM_DECLARATIONS))
            .flatMap(contribution -> runtime.session(project, contribution.id(), "")
                .capability(LanguageCapabilities.JVM_DECLARATIONS).stream())
            .toList();
    }
}
