package dev.railroadide.railroad.ide.ui.refactor;

import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.ide.edit.WorkspaceEditService;
import dev.railroadide.railroad.ide.sst.document.api.*;
import dev.railroadide.railroad.ide.sst.edit.api.WorkspaceEditPreview;
import dev.railroadide.railroad.ide.sst.edit.api.WorkspaceEditProblem;
import dev.railroadide.railroad.ide.sst.edit.api.WorkspaceEditResult;
import dev.railroadide.railroad.ide.sst.provider.api.ProviderExecutor;
import dev.railroadide.railroad.ide.sst.provider.api.RenameConflict;
import dev.railroadide.railroad.ide.sst.provider.api.RenamePreparation;
import dev.railroadide.railroad.ide.sst.provider.api.RenameProposal;
import dev.railroadide.railroad.ide.sst.provider.api.RenameProvider;
import dev.railroadide.railroad.ide.sst.request.api.*;
import dev.railroadide.railroad.localization.L18n;
import dev.railroadide.railroad.ui.RRButton;
import dev.railroadide.railroad.ui.RRHBox;
import dev.railroadide.railroad.ui.RRVBox;
import dev.railroadide.railroad.ui.localized.LocalizedLabel;
import dev.railroadide.railroad.ui.styling.ButtonVariant;
import dev.railroadide.railroad.window.WindowBuilder;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Reviews an inline rename against a captured workspace, and applies the confirmed edit as one undoable step.
 * If another document changes during review, the new proposal requires fresh confirmation.
 */
public final class RenameDialog implements AutoCloseable {
    private static final Duration RENAME_BUDGET = Duration.ofSeconds(60);

    private final Request request;
    private final SstScheduler.Scope requests = SstScheduler.shared().newScope();
    private final String name;
    private final Consumer<Boolean> finished;
    private final RRButton continueButton = RRButton.secondary("railroad.rename.continue");
    private final ScrollPane problems = new ScrollPane();
    private final TitledPane details = new TitledPane();
    private final LocalizedLabel status = new LocalizedLabel("railroad.rename.status.computing");
    private final RRVBox conflictList = new RRVBox(4);
    private final WorkspaceEditPreviewView changes = new WorkspaceEditPreviewView();
    private final RRButton confirmButton = new RRButton("railroad.rename.apply");
    private final RRVBox content;
    private @Nullable Stage stage;
    private @Nullable CancellationTokenSource activeCancellation;
    private @Nullable FeatureRequestContext activeContext;
    private @Nullable RenameProposal proposal;
    private @Nullable WorkspaceSnapshot previewed;
    private boolean refreshed;
    private State state = State.COMPUTING;
    private boolean closed;

    /**
     * Captured inputs of one rename.
     *
     * @param provider rename provider of the document's language session
     * @param providerLifetime lifetime of the provider's session; cancelling it cancels pending computations
     * @param store document store the rename is computed against
     * @param prepared document revision in which the symbol was prepared
     * @param offset UTF-16 offset of the symbol in the prepared revision
     * @param symbol prepared symbol
     * @param edits project workspace-edit service, resolved when the rename is applied
     */
    public record Request(
        RenameProvider provider,
        CancellationToken providerLifetime,
        DocumentStore store,
        TextDocumentSnapshot prepared,
        int offset,
        RenamePreparation.Renameable symbol,
        Supplier<Optional<WorkspaceEditService>> edits
    ) {
        /**
         * Validates that all inputs are present.
         *
         * @param provider rename provider
         * @param providerLifetime provider session lifetime
         * @param store document store
         * @param prepared prepared document revision
         * @param offset UTF-16 offset of the symbol
         * @param symbol prepared symbol
         * @param edits project workspace-edit service supplier
         */
        public Request {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(providerLifetime, "providerLifetime");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(prepared, "prepared");
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(edits, "edits");
        }
    }

    /**
     * Progress of the dialog.
     */
    public enum State {
        /**
         * The rename is being computed.
         */
        COMPUTING,
        /**
         * A preview that can be applied is shown.
         */
        PREVIEWED,
        /**
         * The rename cannot be applied; conflicts or problems are shown.
         */
        BLOCKED,
        /**
         * The rename was applied.
         */
        APPLIED
    }

    /**
     * Builds the dialog without showing it.
     *
     * @param request captured rename inputs
     * @param name name entered inline
     * @param finished called once on close, with true when editing should resume
     */
    public RenameDialog(Request request, String name, Consumer<Boolean> finished) {
        this.request = Objects.requireNonNull(request, "request");
        this.name = Objects.requireNonNull(name, "name");
        this.finished = Objects.requireNonNull(finished, "finished");
        var title = new LocalizedLabel("railroad.rename.confirm", request.symbol().name(), name);
        title.getStyleClass().add("rename-heading");
        title.setWrapText(true);
        status.setWrapText(true);
        conflictList.getStyleClass().add("rename-conflicts");
        problems.setContent(conflictList);
        problems.setFitToWidth(true);
        problems.setPrefViewportHeight(100);

        details.setText(L18n.localize("railroad.rename.changes"));
        details.setContent(changes);
        details.setExpanded(false);
        details.setAnimated(false);
        changes.setPrefHeight(165);
        details.expandedProperty().addListener((_, _, _) -> resize());

        confirmButton.setVariant(ButtonVariant.PRIMARY);
        confirmButton.setDisable(true);
        confirmButton.setOnAction(_ -> apply());
        continueButton.setOnAction(_ -> close(true));
        var discard = RRButton.secondary("railroad.rename.discard");
        discard.setOnAction(_ -> close());
        var buttons = new RRHBox(8, discard, continueButton, confirmButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        content = new RRVBox(10, title, status, problems, details, buttons);
        content.setPadding(new Insets(16));
        content.setPrefWidth(450);
        content.getStyleClass().add("rename-dialog");
        content.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                close();
            }
        });

        clearPreview();
    }

    /**
     * Shows a compact review of the inline edit.
     *
     * @param owner owning editor window
     */
    public void show(@Nullable Window owner) {
        if (closed || stage != null)
            return;

        stage = WindowBuilder.create()
            .title(L18n.localize("railroad.rename.title", request.symbol().kind()), false)
            .owner(owner)
            .modality(Modality.WINDOW_MODAL)
            .scene(new Scene(content))
            .build();
        stage.setOnHidden(_ -> close());
        preview();
    }

    /**
     * Computes the rename for the current name and shows its preview and conflicts.
     *
     * @return future completing on the JavaFX thread once the preview is shown, or exceptionally when the rename
     *         could not be computed
     */
    public CompletableFuture<State> preview() {
        refreshed = false;
        return computePreview();
    }

    private CompletableFuture<State> computePreview() {
        cancelComputation();
        proposal = null;
        previewed = null;
        clearPreview();
        if (closed)
            return CompletableFuture.completedFuture(state);

        WorkspaceSnapshot workspace = request.store().capture();
        if (!(workspace.document(request.prepared().id()).orElse(null) instanceof TextDocumentSnapshot source)
            || !source.text().equals(request.prepared().text())) {
            block("railroad.rename.status.document_changed");
            return CompletableFuture.completedFuture(state);
        }

        var cancellation = new CancellationTokenSource(request.providerLifetime());
        var context = new FeatureRequestContext(workspace, source, cancellation.token(),
            Deadline.after(RENAME_BUDGET));
        activeCancellation = cancellation;
        activeContext = context;
        state = State.COMPUTING;
        status.setKey("railroad.rename.status.computing");
        confirmButton.setDisable(true);

        var shown = new CompletableFuture<State>();
        CompletableFuture<FeatureResult<RenameProposal>> future;
        try {
            future = requests.submit("rename", RequestPriority.NAVIGATION, context, cancellation,
                () -> ProviderExecutor.shared().execute(source.languageId() + ":rename", context, RENAME_BUDGET,
                    () -> request.provider().rename(context, request.offset(), request.symbol().symbol(),
                        name)));
        } catch (RuntimeException exception) {
            future = CompletableFuture.failedFuture(exception);
        }

        future.whenComplete((result, failure) -> Platform.runLater(() -> {
            if (activeContext != context) {
                shown.complete(state);
                return;
            }

            activeContext = null;
            activeCancellation = null;
            if (failure != null || result == null || !result.belongsTo(context)) {
                Throwable cause = unwrap(failure);
                if (cause != null && !(cause instanceof CancellationException)) {
                    Railroad.LOGGER.error("Failed to compute rename", cause);
                }

                block(cause instanceof DeadlineExceededException
                    ? "railroad.rename.status.timed_out"
                    : "railroad.rename.status.failed");
                shown.complete(state);
                return;
            }

            show(result.value(), workspace);
            shown.complete(state);
        }));

        return shown;
    }

    /**
     * Applies the shown preview through the project's workspace-edit service. When the workspace changed since the
     * preview was computed, nothing is applied; the preview is computed again for the prepared symbol and shown for
     * confirmation.
     *
     * @return outcome, or an empty optional when no applicable preview is shown, the project has no edit service, or
     *         the preview was out of date
     */
    public Optional<WorkspaceEditResult> apply() {
        RenameProposal current = proposal;
        WorkspaceSnapshot basis = previewed;
        if (state != State.PREVIEWED || current == null || current.edit() == null || basis == null)
            return Optional.empty();

        Optional<WorkspaceEditService> service = request.edits().get();
        if (service.isEmpty()) {
            block("railroad.rename.status.no_project");
            return Optional.empty();
        }

        if (!sameInputs(basis, request.store().capture(), request.prepared().languageId())) {
            refreshed = true;
            computePreview();
            return Optional.empty();
        }

        WorkspaceEditResult result = service.get().apply(current.edit());
        if (result.applied()) {
            state = State.APPLIED;
            close();
        } else {
            showProblems(result.problems());
            block("railroad.rename.status.apply_failed");
        }

        return Optional.of(result);
    }

    /**
     * Returns the dialog's progress.
     *
     * @return current state
     */
    public State state() {
        return state;
    }

    /**
     * Returns the proposal whose preview is shown.
     *
     * @return shown proposal, or an empty optional
     */
    public Optional<RenameProposal> proposal() {
        return Optional.ofNullable(proposal);
    }

    /**
     * Returns the lines of the shown preview: one per changed file or file rename, followed by its changes.
     *
     * @return preview lines in display order
     */
    public List<String> previewLines() {
        return changes.lines();
    }

    /**
     * Returns the conflict and problem messages shown.
     *
     * @return messages in display order
     */
    public List<String> messages() {
        return conflictList.getChildren().stream().map(node -> ((Label) node).getText()).toList();
    }

    /**
     * Returns the status message shown.
     *
     * @return status text
     */
    public String statusText() {
        return status.getText();
    }

    /**
     * Reports whether the dialog was closed.
     *
     * @return whether the dialog is closed
     */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        close(false);
    }

    private void close(boolean resume) {
        if (closed)
            return;

        closed = true;
        cancelComputation();
        requests.close();
        if (stage != null) {
            stage.setOnHidden(null);
            stage.hide();
        }

        finished.accept(resume);
    }

    private void show(RenameProposal result, WorkspaceSnapshot workspace) {
        proposal = result;
        result.conflicts().forEach(conflict -> addMessage(describe(conflict),
            conflict.severity() == RenameConflict.Severity.ERROR ? "rename-error" : "rename-warning"));
        if (result.edit() == null) {
            block("railroad.rename.status.conflicts");
            return;
        }

        Optional<WorkspaceEditService> service = request.edits().get();
        if (service.isEmpty()) {
            block("railroad.rename.status.no_project");
            return;
        }

        WorkspaceEditPreview preview = service.get().preview(result.edit());
        if (!preview.applicable()) {
            showProblems(preview.problems());
            block("railroad.rename.status.apply_failed");
            return;
        }

        int count = changes.show(preview);

        previewed = workspace;
        state = State.PREVIEWED;
        int files = preview.documents().size();
        String occurrences = L18n.localize(count == 1 ? "railroad.rename.occurrence" : "railroad.rename.occurrences",
            count);
        String affectedFiles = L18n.localize(files == 1 ? "railroad.rename.file" : "railroad.rename.files", files);
        status.setKey(!result.applicable()
            ? "railroad.rename.status.conflicts"
            : refreshed
                ? "railroad.rename.status.refreshed"
                : result.conflicts().isEmpty()
                    ? "railroad.rename.status.ready"
                    : "railroad.rename.status.ready_with_warnings",
            occurrences, affectedFiles);
        confirmButton
            .setLocalizedText(result.conflicts().isEmpty() ? "railroad.rename.apply" : "railroad.rename.apply_anyway");
        confirmButton.setDisable(false);
        boolean clean = result.conflicts().isEmpty();
        confirmButton.setVariant(clean ? ButtonVariant.PRIMARY : ButtonVariant.DANGER);
        confirmButton.setDefaultButton(clean);
        visible(continueButton, !clean);
        (clean ? confirmButton : continueButton).requestFocus();
        visible(problems, !clean);
        visible(details, true);
        resize();
    }

    /**
     * Reports whether two captures have the same configuration and the same documents of the rename's language,
     * with the same locations and content. Those are what the rename was computed from. Versions are ignored, so
     * saving a document without changing it keeps a preview usable.
     */
    private static boolean sameInputs(WorkspaceSnapshot previewed, WorkspaceSnapshot current, String languageId) {
        if (!previewed.configuration().equals(current.configuration()))
            return false;

        Map<DocumentId, DocumentSnapshot> before = inputs(previewed, languageId);
        Map<DocumentId, DocumentSnapshot> after = inputs(current, languageId);
        if (!before.keySet().equals(after.keySet()))
            return false;

        for (DocumentSnapshot document : before.values()) {
            DocumentSnapshot other = after.get(document.id());
            if (!document.uri().equals(other.uri()) || !sameContent(document, other))
                return false;
        }

        return true;
    }

    private static Map<DocumentId, DocumentSnapshot> inputs(WorkspaceSnapshot workspace, String languageId) {
        Map<DocumentId, DocumentSnapshot> inputs = new HashMap<>();
        workspace.documents().forEach((id, document) -> {
            if (document.languageId().equals(languageId)) {
                inputs.put(id, document);
            }
        });

        return inputs;
    }

    private static boolean sameContent(DocumentSnapshot before, DocumentSnapshot after) {
        return switch (before) {
            case TextDocumentSnapshot text -> after instanceof TextDocumentSnapshot other
                && text.text().equals(other.text());
            case BinaryDocumentSnapshot binary -> after instanceof BinaryDocumentSnapshot other
                && binary.bytes().equals(other.bytes());
        };
    }

    private static String describe(RenameConflict conflict) {
        return conflict.message();
    }

    private void showProblems(List<WorkspaceEditProblem> problems) {
        problems.forEach(problem -> addMessage(problem.message()
            + (problem.uri() == null ? "" : " (" + fileName(problem.uri()) + ")"), "rename-error"));
    }

    private void addMessage(String message, String style) {
        var label = new Label(message);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        conflictList.getChildren().add(label);
    }

    private void block(String key) {
        state = State.BLOCKED;
        status.setKey(key);
        confirmButton.setLocalizedText("railroad.rename.apply_anyway");
        confirmButton.setDisable(true);
        confirmButton.setDefaultButton(false);
        visible(problems, !conflictList.getChildren().isEmpty());
        visible(continueButton, true);
        continueButton.requestFocus();
        resize();
    }

    private void clearPreview() {
        conflictList.getChildren().clear();
        changes.clear();
        visible(problems, false);
        visible(continueButton, false);
        visible(details, false);
        confirmButton.setDefaultButton(false);
        confirmButton.setDisable(true);
    }

    private static void visible(Node node, boolean visible) {
        node.setManaged(visible);
        node.setVisible(visible);
    }

    private void resize() {
        if (stage != null) {
            stage.sizeToScene();
        }
    }

    private void cancelComputation() {
        requests.cancel("rename");
        if (activeCancellation != null) {
            activeCancellation.cancel();
            activeCancellation = null;
        }

        activeContext = null;
    }

    private static String fileName(@Nullable DocumentUri uri) {
        return uri == null ? "" : uri.fileName().orElse(uri.toString());
    }

    private static @Nullable Throwable unwrap(@Nullable Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause;
    }
}
