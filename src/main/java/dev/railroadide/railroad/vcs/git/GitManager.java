package dev.railroadide.railroad.vcs.git;

import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.project.data.ProjectDataStore;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GitManager {
    private static final String SETTINGS_PATH = "vcs/git.json";
    private static final long DEFAULT_AUTO_REFRESH_INTERVAL_MILLIS = 5000L;

    private final Project project;
    private final GitClient gitClient;

    private final ScheduledExecutorService executorService;

    private final ObjectProperty<RepoStatus> repoStatus = new SimpleObjectProperty<>();
    private final BooleanProperty active = new SimpleBooleanProperty(false);

    private final ObjectProperty<GitRepository> gitRepository = new SimpleObjectProperty<>();
    private volatile ScheduledFuture<?> autoRefreshFuture;

    public GitManager(Project project, GitClient gitClient, ScheduledExecutorService executorService) {
        this.project = project;
        this.gitClient = gitClient;
        this.executorService = executorService;
    }

    public GitManager(Project project, GitClient gitClient) {
        this(project, gitClient, Executors.newSingleThreadScheduledExecutor());
    }

    public void detectRepository() {
        this.gitClient.detectRepository(this.project.getPath()).ifPresentOrElse(repository -> {
            this.gitRepository.set(repository);
            this.active.set(true);
        }, () -> {
            this.gitRepository.set(null);
            this.active.set(false);
        });
    }

    public void refreshStatus() {
        this.executorService.submit(this::refreshStatusInternal);
    }

    public void startAutoRefresh() {
        if (autoRefreshFuture != null && !autoRefreshFuture.isCancelled() && !autoRefreshFuture.isDone())
            return;

        long intervalMillis = getAutoRefreshIntervalMillis();
        autoRefreshFuture = executorService.scheduleAtFixedRate(
            this::refreshStatusInternal,
            0,
            intervalMillis,
            TimeUnit.MILLISECONDS
        );
    }

    public void stopAutoRefresh() {
        if (this.autoRefreshFuture != null) {
            this.autoRefreshFuture.cancel(false);
            this.autoRefreshFuture = null;
        }
    }

    public void setAutoRefreshIntervalMillis(long intervalMillis) {
        if (intervalMillis <= 0)
            throw new IllegalArgumentException("Auto refresh interval must be positive");

        writeAutoRefreshIntervalMillis(intervalMillis);

        if (autoRefreshFuture != null && !autoRefreshFuture.isCancelled() && !autoRefreshFuture.isDone()) {
            stopAutoRefresh();
            startAutoRefresh();
        }
    }

    public ObjectProperty<RepoStatus> repoStatusProperty() {
        return repoStatus;
    }

    public BooleanProperty activeProperty() {
        return active;
    }

    public ObjectProperty<GitRepository> gitRepositoryProperty() {
        return gitRepository;
    }

    public RepoStatus getRepoStatus() {
        return repoStatus.get();
    }

    public boolean isActive() {
        return active.get();
    }

    public GitRepository getGitRepository() {
        return gitRepository.get();
    }

    private void refreshStatusInternal() {
        GitRepository repository = this.gitRepository.get();
        if (repository != null) {
            RepoStatus status = this.gitClient.getStatus(repository);
            this.repoStatus.set(status);
        } else {
            this.repoStatus.set(null);
        }
    }

    public GitSettings getGitSettings() {
        ProjectDataStore dataStore = project.getDataStore();
        return dataStore.readJson(SETTINGS_PATH, GitSettings.class).orElseGet(GitSettings::new);
    }

    public GitSettings getOrCreateGitSettings() {
        ProjectDataStore dataStore = project.getDataStore();
        Optional<GitSettings> settingsOpt = dataStore.readJson(SETTINGS_PATH, GitSettings.class);
        if (settingsOpt.isPresent()) {
            return settingsOpt.get();
        } else {
            var settings = new GitSettings();
            settings.setAutoRefreshIntervalMillis(DEFAULT_AUTO_REFRESH_INTERVAL_MILLIS);
            dataStore.writeJson(SETTINGS_PATH, settings);
            return settings;
        }
    }

    public void saveGitSettings(GitSettings settings) {
        ProjectDataStore dataStore = project.getDataStore();
        dataStore.writeJson(SETTINGS_PATH, settings);
    }

    private long getAutoRefreshIntervalMillis() {
        ProjectDataStore dataStore = project.getDataStore();
        Optional<GitSettings> settings = dataStore.readJson(SETTINGS_PATH, GitSettings.class);
        Long interval = settings.map(GitSettings::getAutoRefreshIntervalMillis).orElse(null);
        if (interval == null || interval <= 0) {
            writeAutoRefreshIntervalMillis(DEFAULT_AUTO_REFRESH_INTERVAL_MILLIS);
            return DEFAULT_AUTO_REFRESH_INTERVAL_MILLIS;
        }

        return interval;
    }

    private void writeAutoRefreshIntervalMillis(long intervalMillis) {
        var settings = new GitSettings();
        settings.setAutoRefreshIntervalMillis(intervalMillis);
        project.getDataStore().writeJson(SETTINGS_PATH, settings);
    }

    public void setGitExecutablePath(Path path) {
        this.gitClient.runner.setGitExecutable(path);
    }
}
