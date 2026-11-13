package dev.railroadide.railroad.ide.diagnostics;

import dev.railroadide.railroad.ide.sst.document.api.Location;
import dev.railroadide.railroad.ide.sst.document.api.Location.BinaryLocation;
import dev.railroadide.railroad.ide.sst.document.api.Location.TextLocation;
import dev.railroadide.railroad.ide.sst.document.api.RelatedLocation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, language-neutral diagnostic published by all editor-facing providers.
 * Locations identify the exact document revision; a result for an older revision must not be displayed.
 * Source and origin distinguish overlapping reports without silently discarding their provenance.
 * Opaque data is a provider-owned immutable string, interpreted only by its originating provider.
 *
 * @param <L> the location type identifying the affected document revision and range
 */
public sealed interface EditorDiagnostic<L extends Location<?>>
    permits EditorDiagnostic.TextEditorDiagnostic, EditorDiagnostic.BinaryEditorDiagnostic {
    public L location();

    public DiagnosticSeverity kind();

    public String code();

    public String message(Locale locale);

    /** Returns the stable identifier of the provider that produced this report. */
    public String source();

    public DiagnosticOrigin origin();

    public Set<DiagnosticTag> tags();

    public List<RelatedLocation> relatedInformation();

    /** Returns provider-owned opaque data, or null when no data is attached. */
    public String data();

    /** A diagnostic referring to a text document revision. */
    public record TextEditorDiagnostic(
        TextLocation location,
        DiagnosticSeverity kind,
        String code,
        String message,
        String source,
        DiagnosticOrigin origin,
        Set<DiagnosticTag> tags,
        List<RelatedLocation> relatedInformation,
        String data
    ) implements EditorDiagnostic<TextLocation> {
        public TextEditorDiagnostic {
            validate(location, kind, message, source, origin);
            tags = Set.copyOf(tags);
            relatedInformation = List.copyOf(relatedInformation);
        }

        public TextEditorDiagnostic(TextLocation location, DiagnosticSeverity kind, String code, String message) {
            this(location, kind, code, message, "unspecified", DiagnosticOrigin.SEMANTIC, Set.of(), List.of(), null);
        }

        @Override
        public String message(Locale locale) {
            return message;
        }
    }

    /** A diagnostic referring to a binary document revision. */
    public record BinaryEditorDiagnostic(
        BinaryLocation location,
        DiagnosticSeverity kind,
        String code,
        String message,
        String source,
        DiagnosticOrigin origin,
        Set<DiagnosticTag> tags,
        List<RelatedLocation> relatedInformation,
        String data
    ) implements EditorDiagnostic<BinaryLocation> {
        public BinaryEditorDiagnostic {
            validate(location, kind, message, source, origin);
            tags = Set.copyOf(tags);
            relatedInformation = List.copyOf(relatedInformation);
        }

        public BinaryEditorDiagnostic(BinaryLocation location, DiagnosticSeverity kind, String code, String message) {
            this(location, kind, code, message, "unspecified", DiagnosticOrigin.SEMANTIC, Set.of(), List.of(), null);
        }

        @Override
        public String message(Locale locale) {
            return message;
        }
    }

    private static void validate(
        Location<?> location,
        DiagnosticSeverity kind,
        String message,
        String source,
        DiagnosticOrigin origin
    ) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(origin, "origin");
        if (message.isBlank())
            throw new IllegalArgumentException("Diagnostic message must not be blank");
        if (source.isBlank())
            throw new IllegalArgumentException("Diagnostic source must not be blank");
    }
}
