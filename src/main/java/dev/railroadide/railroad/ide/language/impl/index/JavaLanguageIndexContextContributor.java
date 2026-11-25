package dev.railroadide.railroad.ide.language.impl.index;

import dev.railroadide.railroad.ide.language.impl.JavaLanguageSupport;
import dev.railroadide.railroad.ide.language.index.LanguageIndexContextContributor;
import dev.railroadide.railroad.ide.language.jvm.JvmIndexContext;
import dev.railroadide.railroad.ide.language.jvm.JvmProjectResolver;
import dev.railroadide.railroad.plugin.spi.dto.Project;

/**
 * Supplies Java indexing inputs from the shared JVM project model.
 */
public final class JavaLanguageIndexContextContributor implements LanguageIndexContextContributor {
    @Override
    public String languageId() {
        return JavaLanguageSupport.LANGUAGE_ID;
    }

    @Override
    public JvmIndexContext resolve(Project project) {
        return new JvmIndexContext(JavaLanguageSupport.LANGUAGE_ID, JvmProjectResolver.resolve(project));
    }
}
