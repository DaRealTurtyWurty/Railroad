package dev.railroadide.railroad.vcs.git;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.project.Project;
import dev.railroadide.railroad.project.data.ProjectDataStore;
import dev.railroadide.railroad.vcs.git.commit.GitCommitData;
import dev.railroadide.railroad.vcs.git.commit.GitCommitPage;
import dev.railroadide.railroad.vcs.git.execution.GitOutputListener;
import dev.railroadide.railroad.vcs.git.execution.GitResult;
import dev.railroadide.railroad.vcs.git.execution.progress.GitProgressEvent;
import dev.railroadide.railroad.vcs.git.execution.progress.GitResultCaptureMode;
import dev.railroadide.railroad.vcs.git.identity.GitIdentity;
import dev.railroadide.railroad.vcs.git.remote.GitRemote;
import dev.railroadide.railroad.vcs.git.remote.GitUpstream;
import dev.railroadide.railroad.vcs.git.status.GitRepoStatus;
import dev.railroadide.railroad.vcs.git.util.GitRepository;
import dev.railroadide.railroad.vcs.git.util.GitSettings;
import javafx.beans.property.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

public class GitManager {
    private static final String SETTINGS_PATH = "vcs/git.json";
    private static final long DEFAULT_AUTO_REFRESH_INTERVAL_MILLIS = 5000L;

    private final Project project;
    private final GitClient gitClient;

    private final ScheduledExecutorService executorService;

    private final ObjectProperty<GitRepoStatus> repoStatus = new SimpleObjectProperty<>();
    private final BooleanProperty active = new SimpleBooleanProperty(false);
    private final ObjectProperty<GitRepository> gitRepository = new SimpleObjectProperty<>();
    private final LongProperty lastFetchTimestamp = new SimpleLongProperty(0L);
    private final ObjectProperty<GitIdentity> gitIdentity = new SimpleObjectProperty<>();

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
            startAutoRefresh();
            loadIdentity();
            fetch();
        }, () -> {
            this.gitRepository.set(null);
            this.active.set(false);
            stopAutoRefresh();
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

    public ObjectProperty<GitRepoStatus> repoStatusProperty() {
        return repoStatus;
    }

    public BooleanProperty activeProperty() {
        return active;
    }

    public ObjectProperty<GitRepository> gitRepositoryProperty() {
        return gitRepository;
    }

    public GitRepoStatus getRepoStatus() {
        return repoStatus.get();
    }

    public boolean isActive() {
        return active.get();
    }

    public GitRepository getGitRepository() {
        return gitRepository.get();
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

    public void setGitExecutablePath(Path path) {
        this.gitClient.runner.setGitExecutable(path);
    }

    public void commitChanges(GitCommitData commit, boolean pushAfterCommit) {
        this.executorService.submit(() -> {
            gitClient.commitChanges(this.gitRepository.get(), commit, pushAfterCommit);
            refreshStatusInternal();
        });
    }

    public List<GitRemote> getRemotes() {
        GitRepository repository = this.gitRepository.get();
        if (repository != null) {
            return this.gitClient.getRemotes(repository);
        } else {
            return List.of();
        }
    }

    public Optional<GitUpstream> getUpstream() {
        GitRepository repository = this.gitRepository.get();
        if (repository != null) {
            return this.gitClient.getUpstream(repository);
        } else {
            return Optional.empty();
        }
    }

    public void fetch() {
        this.executorService.submit(() -> {
            GitRepository repository = this.gitRepository.get();
            if (repository != null) {
                this.gitClient.fetch(repository, GitOutputListener.NO_OP, event -> {
                    if (event instanceof GitProgressEvent.Percentage(String phase, int percent)) {
                        Railroad.LOGGER.debug("Git Fetch Progress - {}: {}%", phase, percent);
                    } else if (event instanceof GitProgressEvent.Message(String message)) {
                        Railroad.LOGGER.debug("Git Fetch Message - {}", message);
                    }
                });
                this.lastFetchTimestamp.set(System.currentTimeMillis());
                refreshStatusInternal();
            }
        });
    }

    public LongProperty lastFetchTimestampProperty() {
        return lastFetchTimestamp;
    }

    public long getLastFetchTimestamp() {
        return lastFetchTimestamp.get();
    }

    private void refreshStatusInternal() {
        GitRepository repository = this.gitRepository.get();
        if (repository != null) {
            GitRepoStatus status = this.gitClient.getStatus(repository);
            this.repoStatus.set(status);
//            Railroad.LOGGER.debug("Loaded {} changes from Git repository at {}",
//                status.changes().size(),
//                repository.root());
        } else {
            this.repoStatus.set(null);
        }
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

    public void push() {
        this.executorService.submit(() -> {
            GitRepository repository = this.gitRepository.get();
            if (repository != null) {
                this.gitClient.push(repository, GitOutputListener.NO_OP, event -> {
                    if (event instanceof GitProgressEvent.Percentage(String phase, int percent)) {
                        Railroad.LOGGER.debug("Git Push Progress - {}: {}%", phase, percent);
                    } else if (event instanceof GitProgressEvent.Message(String message)) {
                        Railroad.LOGGER.debug("Git Push Message - {}", message);
                    }
                });
                refreshStatusInternal();
            }
        });
    }

    public void pull() {
        this.executorService.submit(() -> {
            GitRepository repository = this.gitRepository.get();
            if (repository != null) {
                this.gitClient.pull(repository, GitOutputListener.NO_OP, event -> {
                    if (event instanceof GitProgressEvent.Percentage(String phase, int percent)) {
                        Railroad.LOGGER.debug("Git Pull Progress - {}: {}%", phase, percent);
                    } else if (event instanceof GitProgressEvent.Message(String message)) {
                        Railroad.LOGGER.debug("Git Pull Message - {}", message);
                    }
                });
                refreshStatusInternal();
            }
        });
    }

    public ObjectProperty<GitIdentity> gitIdentityProperty() {
        return gitIdentity;
    }

    public GitIdentity getIdentity() {
        return gitIdentityProperty().get();
    }

    public void loadIdentity() {
        this.executorService.submit(() -> {
            try {
                GitIdentity identity = this.gitClient.getIdentity();
                this.gitIdentity.set(identity);
                Railroad.LOGGER.debug("Loaded Git identity: {}", identity);
            } catch (Exception exception) {
                Railroad.LOGGER.warn("Failed to load Git identity", exception);
            }
        });
    }

    public CompletableFuture<Optional<GitCommitPage>> getRecentCommits(int count) {
        return CompletableFuture.supplyAsync(() -> {
            GitRepository repository = this.gitRepository.get();
            if (repository != null) {
                return Optional.ofNullable(this.gitClient.getRecentCommits(repository, null, count));
            } else {
                return Optional.empty();
            }
        }, executorService);
    }

    public Optional<String> getUnstagedDiff(Path filePath) {
        GitRepository repository = this.gitRepository.get();
        if (repository == null || filePath == null)
            return Optional.empty();

        Path repoRoot = repository.root().toAbsolutePath().normalize();
        Path absoluteFile = filePath.toAbsolutePath().normalize();
        if (!absoluteFile.startsWith(repoRoot))
            return Optional.empty();

        Path relativePath = repoRoot.relativize(absoluteFile);
        GitCommand cmd = GitCommands.getUnstagedDiff(repository, relativePath);
        GitResult result = gitClient.runner.run(cmd, null, null, GitResultCaptureMode.TEXT_WHOLE);

        if (result.timedOut() || result.cancelled()) {
            Railroad.LOGGER.warn("git diff was {} for path: {}", result.timedOut() ? "timed out" : "cancelled", filePath);
            return Optional.empty();
        }

        if (result.exitCode() != 0) {
            Railroad.LOGGER.warn("git diff failed for path {}: {}", filePath, String.join("\n", result.stderr()));
            return Optional.empty();
        }

        String diffText = result.readAllStdout();
        return Optional.of(diffText);
    }
}
