package dev.railroadide.railroad.ide.sst.provider.api;

import dev.railroadide.railroad.ide.sst.document.api.ContentKind;
import java.util.Set;

/**
 * Plugin-owned language registration. Descriptor methods must return stable immutable values for
 * the registration lifetime. Implementations must be safe for concurrent discovery and session creation.
 * Session services must expose public interface contracts and support concurrent request invocation.
 */
public interface LanguageContribution {
    LanguageId id();

    String displayName();

    LanguageMatcher matcher();

    default ContentKind contentKind() {
        return ContentKind.TEXT;
    }

    /** Declares optional contracts before a project session is created. */
    default Set<CapabilityKey<?>> supportedCapabilities() {
        return Set.of();
    }

    default int priority() {
        return 0;
    }

    /** Opens one project/dialect session; the runtime owns and disposes the returned session. */
    LanguageSession openSession(LanguageSessionContext context);
}
