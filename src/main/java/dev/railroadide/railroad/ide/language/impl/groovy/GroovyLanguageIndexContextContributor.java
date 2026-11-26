package dev.railroadide.railroad.ide.language.impl.groovy;

import dev.railroadide.railroad.ide.language.impl.GroovyLanguageSupport;
import dev.railroadide.railroad.ide.language.index.LanguageIndexContextContributor;
import dev.railroadide.railroad.ide.language.jvm.JvmIndexContext;
import dev.railroadide.railroad.ide.language.jvm.JvmProjectResolver;
import dev.railroadide.railroad.plugin.spi.dto.Project;

/**
 * Supplies Groovy indexing inputs from the shared JVM project model.
 */
public final class GroovyLanguageIndexContextContributor implements LanguageIndexContextContributor {
    @Override
    public String languageId() {
        return GroovyLanguageSupport.LANGUAGE_ID;
    }

    @Override
    public JvmIndexContext resolve(Project project) {
        return new JvmIndexContext(GroovyLanguageSupport.LANGUAGE_ID, JvmProjectResolver.resolve(project));
    }
}
