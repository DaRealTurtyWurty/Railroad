package dev.railroadide.railroad.ide.ui.navigation.quickopen;

import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.language.LanguageSupport;
import dev.railroadide.railroad.ide.language.LanguageSupportRegistry;
import dev.railroadide.railroad.ide.language.index.ProjectLanguageIndex;
import dev.railroadide.railroad.ide.language.index.ProjectTypeIndex;
import dev.railroadide.railroad.ide.language.index.ProjectTypeIndex.ProjectType;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import javafx.scene.Node;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists the types declared in the project's sources, as recorded by every language's project index.
 */
public final class GoToClassContributor implements GoToContributor<ProjectType> {
    @Override
    public String titleKey() {
        return "railroad.ide.go_to.class.title";
    }

    @Override
    public List<ProjectType> collect(Project project, Runnable activityCheck) {
        var service = Services.PROJECT_LANGUAGE_INDEX_SERVICE;
        List<ProjectTypeIndex> indexes = new ArrayList<>();
        for (LanguageSupport support : LanguageSupportRegistry.all()) {
            activityCheck.run();
            String languageId = support.languageId();
            if (!service.hasIndexer(project, languageId))
                continue;

            ProjectLanguageIndex<?> index = service.current(project, languageId);
            if (index == null) {
                activityCheck.run();
                index = service.index(project, languageId);
            }

            if (index instanceof ProjectTypeIndex types) {
                indexes.add(types);
            }
        }

        return types(indexes, activityCheck);
    }

    /**
     * Collects the type declarations of several language indexes.
     *
     * @param indexes project indexes
     * @param activityCheck cancellation check
     * @return declared types
     */
    public static List<ProjectType> types(List<? extends ProjectTypeIndex> indexes, Runnable activityCheck) {
        List<ProjectType> types = new ArrayList<>();
        for (ProjectTypeIndex index : indexes) {
            activityCheck.run();
            types.addAll(index.types(activityCheck));
        }

        return types;
    }

    /**
     * Finds the name of a type declaration in source text. The file may have changed since it was indexed, so the
     * indexed offset is only used while a declaration of the name still starts there; otherwise the first declaration
     * of the name is used.
     *
     * @param text current source text
     * @param simpleName type name
     * @param indexedOffset offset recorded by the index, or {@code -1}
     * @return offset of the name, or {@code -1} when no declaration is found
     */
    public static int declarationOffset(String text, String simpleName, int indexedOffset) {
        Matcher matcher = Pattern.compile("(?<![\\w$])(?:class|interface|enum|record|trait)\\s+("
            + Pattern.quote(simpleName) + ")(?![\\w$])").matcher(text);
        int first = -1;
        while (matcher.find()) {
            if (matcher.start(1) == indexedOffset)
                return indexedOffset;

            if (first < 0) {
                first = matcher.start(1);
            }
        }

        return first;
    }

    @Override
    public String name(ProjectType item) {
        return item.simpleName();
    }

    @Override
    public String path(ProjectType item) {
        return item.qualifiedName();
    }

    @Override
    public String detail(ProjectType item) {
        String qualifiedName = item.qualifiedName();
        int separator = qualifiedName.length() - item.simpleName().length() - 1;
        return separator > 0 && qualifiedName.endsWith(item.simpleName()) ? qualifiedName.substring(0, separator) : "";
    }

    @Override
    public char separator() {
        return '.';
    }

    @Override
    public Node icon(ProjectType item) {
        var badge = new Label(switch (item.kind()) {
            case INTERFACE -> "I";
            case ENUM -> "E";
            case RECORD -> "R";
            case ANNOTATION -> "@";
            case TRAIT -> "T";
            default -> "C";
        });

        badge.getStyleClass().addAll("go-to-type-badge",
            "go-to-type-" + item.kind().name().toLowerCase(Locale.ROOT));
        return badge;
    }

    @Override
    public void open(ProjectType item, GoToSearch.Query query) {
        GoToContributor.openInEditor(item.file(), text -> query.hasLine()
            ? query.offsetIn(text)
            : declarationOffset(text, item.simpleName(), item.nameOffset()));
    }
}
