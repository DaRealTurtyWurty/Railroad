package dev.railroadide.railroad.ide.language.index;

import dev.railroadide.railroad.ide.language.LanguageCapabilities;
import dev.railroadide.railroad.ide.language.LanguageRuntime;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceConfigurationSnapshot;
import dev.railroadide.railroad.plugin.spi.dto.Project;

import java.util.List;

/**
 * Session service through which a language takes part in publishing a project's workspace configuration, for example
 * to fingerprint its binary inputs and replace caches that depend on them.
 * <p>
 * Each time the configuration's inputs change, the host passes the candidate configuration through every
 * participant's {@link #prepare} on a background worker, then calls {@link #adopt} with the final configuration
 * immediately before publishing it. A newer request supersedes an older one at any stage, so a prepared configuration
 * may never be adopted. Participants add only settings whose keys start with {@code index.}; those keys are not
 * treated as configuration inputs.
 */
public interface WorkspaceConfigurationParticipant {
    /**
     * Adds this participant's derived settings to a candidate configuration. Runs on a background worker and may be
     * slow; it must stop with {@link java.util.concurrent.CancellationException} once the session closes.
     *
     * @param session project index session that will own the configuration
     * @param configuration candidate configuration, including settings added by earlier participants
     * @return the configuration with this participant's settings added
     */
    WorkspaceConfigurationSnapshot prepare(
        ProjectLanguageIndexService.Session session,
        WorkspaceConfigurationSnapshot configuration
    );

    /**
     * Switches this participant's session state to a configuration that is about to be published.
     *
     * @param session project index session owning the configuration
     * @param configuration final configuration returned through every participant's {@link #prepare}
     */
    void adopt(ProjectLanguageIndexService.Session session, WorkspaceConfigurationSnapshot configuration);

    /**
     * Releases state owned by a project index session that is closing. Unknown sessions have no effect.
     *
     * @param session closing project index session
     */
    void release(ProjectLanguageIndexService.Session session);

    /**
     * Collects the participants of every language registered in the shared runtime, opening their project sessions
     * as needed.
     *
     * @param project project whose language sessions supply the participants
     * @return participants in language identifier order
     */
    static List<WorkspaceConfigurationParticipant> of(Project project) {
        LanguageRuntime runtime = LanguageSupportRegistry.runtime();
        return runtime.all().stream()
            .filter(contribution -> contribution.supportedCapabilities()
                .contains(LanguageCapabilities.WORKSPACE_CONFIGURATION))
            .flatMap(contribution -> runtime.session(project, contribution.id(), "")
                .capability(LanguageCapabilities.WORKSPACE_CONFIGURATION).stream())
            .toList();
    }
}
