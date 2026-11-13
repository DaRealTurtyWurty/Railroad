package dev.railroadide.railroad.ide.language.index;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.language.LanguageSupport;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.sst.request.api.RequestContext;
import dev.railroadide.railroad.ide.sst.provider.api.ProviderExecutor;
import java.time.Duration;
import java.util.function.Consumer;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.plugin.spi.events.ProjectConfigurationChangedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Applies project-wide language index lifecycle updates in response to file system events.
 */
public final class ProjectLanguageIndexCoordinator {
    private volatile ProjectIndexContext context;
    private final ProjectLanguageIndexService indexService;
    private final List<LanguageSupport> supports;
    private final ProviderExecutor providers;
    private final Duration providerBudget;

    /**
     * Creates a coordinator for warming indexes and applying project file events.
     *
     * @param project the project whose files and configuration are used
     */
    public ProjectLanguageIndexCoordinator(Project project) {
        this(project, Services.PROJECT_LANGUAGE_INDEX_SERVICE.session(project));
    }

    /**
     * Creates a coordinator retaining the session captured before its worker was submitted.
     *
     * @param project the project whose files and configuration are used
     * @param session captured index ownership, or {@code null} when no live session exists
     */
    public ProjectLanguageIndexCoordinator(Project project, ProjectLanguageIndexService.Session session) {
        this(new DefaultProjectIndexContextResolver(_ -> session).resolve(project),
            Services.PROJECT_LANGUAGE_INDEX_SERVICE,
            LanguageSupportRegistry.all());
    }

    /**
     * Creates a coordinator for warming indexes and applying project file events.
     *
     * @param context the project indexing context
     * @param indexService the service managing project indexes
     * @param supports the language support implementations
     */
    public ProjectLanguageIndexCoordinator(
        ProjectIndexContext context,
        ProjectLanguageIndexService indexService,
        Collection<LanguageSupport> supports
    ) {
        this(context, indexService, supports, ProviderExecutor.shared(), Duration.ofMinutes(2));
    }

    /**
     * Creates a coordinator with an explicitly owned provider execution boundary and budget.
     * The coordinator does not close that shared boundary.
     *
     * @param context project configuration and index session
     * @param indexService index publication owner
     * @param supports registered language providers
     * @param providers provider isolation and measurement boundary
     * @param providerBudget maximum duration of one language's source or auxiliary index work
     */
    public ProjectLanguageIndexCoordinator(
        ProjectIndexContext context,
        ProjectLanguageIndexService indexService,
        Collection<LanguageSupport> supports,
        ProviderExecutor providers,
        Duration providerBudget
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.providerBudget = Objects.requireNonNull(providerBudget, "providerBudget");
        if (providerBudget.isNegative())
            throw new IllegalArgumentException("Provider budget must not be negative");
        this.indexService = Objects.requireNonNull(indexService, "indexService");
        this.supports = List.copyOf(Objects.requireNonNull(supports, "supports"));
    }

    /**
     * Warms each supported project index and its auxiliary indexes, logging failures independently.
     */
    public void warmIndexes() {
        warmIndexes(null);
    }

    /**
     * Warms indexes using scheduler/provider cancellation and deadline controls.
     * Request controls are confined to this invocation and never retained for later file events.
     *
     * @param request captured execution controls, or null for synchronous session-owned work
     */
    public void warmIndexes(RequestContext request) {
        ProjectIndexContext operation = withRequest(context, request);
        for (LanguageSupport support : supports) {
            operation.checkActive();
            if (!indexService.hasIndexer(support.languageId()))
                continue;

            try {
                runProvider(support, request, "source", operation,
                    child -> indexService.index(child, support.languageId()));
            } catch (CancellationException exception) {
                if (request == null)
                    throw exception;
                request.checkActive();
                operation.checkActive();
                continue;
            } catch (RuntimeException exception) {
                Railroad.LOGGER.warn(
                    "Failed to warm project index for language {} in {}",
                    support.languageId(),
                    operation.projectRoot(),
                    exception);
            }

            try {
                runProvider(support, request, "auxiliary", operation, support::warmAdditionalIndexes);
            } catch (CancellationException exception) {
                if (request == null)
                    throw exception;
                request.checkActive();
                operation.checkActive();
            } catch (RuntimeException exception) {
                Railroad.LOGGER.warn(
                    "Failed to warm additional indexes for language {} in {}",
                    support.languageId(),
                    operation.projectRoot(),
                    exception);
            }
        }
    }

    /**
     * Applies supported file creation, modification, and deletion events to the matching language index.
     *
     * @param path the file path to inspect
     * @param kind the file watcher event kind
     */
    public void handleFileChange(Path path, WatchEvent.Kind<?> kind) {
        handleFileChange(path, kind, null);
    }

    /**
     * Applies a file event with request controls checked by the index service before publication.
     *
     * @param path changed file
     * @param kind watcher event kind
     * @param request captured execution controls, or null for synchronous session-owned work
     */
    public void handleFileChange(Path path, WatchEvent.Kind<?> kind, RequestContext request) {
        ProjectIndexContext previous = context;
        if (previous.session() == null || previous.session().isClosed())
            return;
        if (previous.configurationGeneration() != previous.session().configurationGeneration()) {
            context = new DefaultProjectIndexContextResolver(_ -> previous.session()).resolve(previous.project());
        }
        ProjectIndexContext operation = withRequest(context, request);
        operation.checkActive();
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        if (List
            .of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                "gradle.properties")
            .contains(path.getFileName().toString())) {
            Railroad.EVENT_BUS.publish(new ProjectConfigurationChangedEvent(context.project()));
        }

        LanguageSupport support = findSupport(path);
        if (support == null || !indexService.hasIndexer(support.languageId()))
            return;

        try {
            if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                runProvider(support, request, "source", operation,
                    child -> indexService.removeFile(child, support.languageId(), path));
                return;
            }

            if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                if (Files.isDirectory(path))
                    return;

                runProvider(support, request, "source", operation,
                    child -> indexService.updateFile(child, support.languageId(), path));
            }
        } catch (CancellationException exception) {
            indexService.invalidate(operation.project(), support.languageId());
            if (request == null)
                throw exception;
            request.checkActive();
            operation.checkActive();
        } catch (RuntimeException exception) {
            indexService.invalidate(operation.project(), support.languageId());
            Railroad.LOGGER.warn(
                "Failed to update project index for language {} and path {}",
                support.languageId(),
                path,
                exception);
        }
    }

    private void runProvider(
        LanguageSupport support,
        RequestContext request,
        String operation,
        ProjectIndexContext inputs,
        Consumer<ProjectIndexContext> work
    ) {
        if (request == null) {
            work.accept(inputs);
            return;
        }
        providers.executeWithContext("project-index:" + support.languageId() + ":"
            + support.getClass().getName() + ":" + operation, request, providerBudget, child -> {
                work.accept(withRequest(inputs, child));
                return null;
            });
    }

    private static ProjectIndexContext withRequest(ProjectIndexContext context, RequestContext request) {
        if (request == null)
            return context;
        request.checkActive();
        return new ProjectIndexContext(context.project(), context.projectRoot(), context.languages(), context.session(),
            context.configurationGeneration(), () -> context.cancellationToken().isCancellationRequested()
                || request.cancellationToken().isCancellationRequested() || request.deadline().isExpired());
    }

    private LanguageSupport findSupport(Path path) {
        for (LanguageSupport support : supports) {
            if (support.supports(path))
                return support;
        }

        return null;
    }
}
