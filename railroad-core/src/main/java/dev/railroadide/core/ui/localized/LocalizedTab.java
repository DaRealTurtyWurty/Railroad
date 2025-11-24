package dev.railroadide.core.ui.localized;

import dev.railroadide.core.localization.LocalizationService;
import dev.railroadide.core.utility.ServiceLocator;
import javafx.scene.Node;
import javafx.scene.control.Tab;

/**
 * An extension of the JavaFX Tab that allows for the Tab's text to be localised.
 */
public class LocalizedTab extends Tab {
    private String currentKey;

    public LocalizedTab(String titleKey) {
        super();
        setKey(titleKey);
        setText(ServiceLocator.getService(LocalizationService.class).get(titleKey));
    }

    public LocalizedTab() {
        super();
    }

    public LocalizedTab(String titleKey, Node content) {
        this(titleKey);
        setContent(content);
    }

    /**
     * Gets the current key used for localization.
     *
     * @return The current localization key.
     */
    public String getKey() {
        return currentKey;
    }

    /**
     * Sets the key and then updates the text of the label.
     * Adds a listener to the current language property to update the text when the language changes.
     *
     * @param key The localization key
     */
    public void setKey(final String key) {
        currentKey = key;
        LocalizationService l18n = ServiceLocator.getService(LocalizationService.class);
        l18n.currentLanguageProperty().addListener((observable, oldValue, newValue) ->
            setText(l18n.get(key)));
        setText(l18n.get(currentKey));
    }
}
