package dev.railroadide.core.ui;

import dev.railroadide.core.localization.LocalizationService;
import dev.railroadide.core.utility.ServiceLocator;
import javafx.animation.ScaleTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.BooleanPropertyBase;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.util.Duration;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * A modern button component with enhanced styling, variants, and smooth animations.
 * Supports different sizes, styles, and icon integration.
 */
public class RRButton extends Button {
    private final RRButtonBase<RRButton> support;

    public RRButton() {
        this("");
    }

    public RRButton(String localizationKey, Ikon icon, Object... args) {
        super((localizationKey != null && !localizationKey.isBlank()) ? ServiceLocator.getService(LocalizationService.class).get(localizationKey) : "");
        support = new RRButtonBase<>(this);
        support.setIcon(icon);
        support.initialize();
        support.trackLocalization(localizationKey, args);
    }

    public RRButton(String localizationKey, Node graphic, Object... args) {
        super((localizationKey != null && !localizationKey.isBlank()) ? ServiceLocator.getService(LocalizationService.class).get(localizationKey) : "");
        setGraphic(graphic);
        support = new RRButtonBase<>(this);
        support.initialize();
        support.trackLocalization(localizationKey, args);
    }

    public RRButton(String localizationKey, Object... args) {
        super(ServiceLocator.getService(LocalizationService.class).get(localizationKey, args));
        support = new RRButtonBase<>(this);
        support.initialize();
        support.trackLocalization(localizationKey, args);
    }

    /**
     * Create a primary button
     */
    public static RRButton primary(String text) {
        var button = new RRButton(text);
        button.setVariant(ButtonVariant.PRIMARY);
        return button;
    }

    /**
     * Create a secondary button
     */
    public static RRButton secondary(String text) {
        var button = new RRButton(text);
        button.setVariant(ButtonVariant.SECONDARY);
        return button;
    }

    /**
     * Create a ghost button
     */
    public static RRButton ghost(String text) {
        var button = new RRButton(text);
        button.setVariant(ButtonVariant.GHOST);
        return button;
    }

    /**
     * Create a danger button
     */
    public static RRButton danger(String text) {
        var button = new RRButton(text);
        button.setVariant(ButtonVariant.DANGER);
        return button;
    }

    /**
     * Create a success button
     */
    public static RRButton success(String text) {
        var button = new RRButton(text);
        button.setVariant(ButtonVariant.SUCCESS);
        return button;
    }

    /**
     * Create a warning button
     */
    public static RRButton warning(String text) {
        var button = new RRButton(text);
        button.setVariant(ButtonVariant.WARNING);
        return button;
    }

    /**
     * Set the button text using a localization key with optional formatting arguments.
     * The text will automatically update when the application language changes.
     *
     * @param localizationKey the localization key for the text
     * @param args            optional formatting arguments for the localized text
     */
    public void setLocalizedText(String localizationKey, Object... args) {
        support.setLocalizedText(localizationKey, args);
    }

    /**
     * Set the button variant
     */
    public void setVariant(ButtonVariant variant) {
        support.setVariant(variant);
    }

    /**
     * Set the button size
     */
    public void setButtonSize(ButtonSize size) {
        support.setButtonSize(size);
    }

    /**
     * Set an icon for the button
     */
    public void setIcon(Ikon iconCode) {
        support.setIcon(iconCode);
    }

    /**
     * Set loading state for the button.
     * <p>
     * When loading is true:
     * - The button becomes disabled and shows a spinning icon
     * - The text changes to "Loading..." if there was original text
     * - The button gets a "loading" CSS class for styling
     * - Click animations are disabled during loading
     * <p>
     * When loading is false:
     * - The button is re-enabled and shows the original content
     * - Original text and icon are restored
     * - The "loading" CSS class is removed
     * <p>
     * Example usage:
     * <pre>
     * RRButton button = RRButton.primary("Save");
     * button.setOnAction(e -> {
     *     button.setLoading(true);
     *     // Perform async operation
     *     CompletableFuture.runAsync(() -> {
     *         // Do work...
     *         Platform.runLater(() -> button.setLoading(false));
     *     });
     * });
     * </pre>
     *
     * @param loading true to show loading state, false to restore normal state
     */
    public void setLoading(boolean loading) {
        support.setLoading(loading);
    }

    public boolean isLoading() {
        return support.isLoading();
    }

    /**
     * Set the button as rounded
     */
    public void setRounded(boolean rounded) {
        support.setRounded(rounded);
    }

    /**
     * Force the button into a square shape.
     */
    public void setSquare(boolean square) {
        support.setSquare(square);
    }

    /**
     * Set the button as outlined
     */
    public void setOutlined(boolean outlined) {
        support.setOutlined(outlined);
    }

    /**
     * Set the button as flat
     */
    public void setFlat(boolean flat) {
        support.setFlat(flat);
    }

    public enum ButtonVariant {
        PRIMARY, SECONDARY, GHOST, DANGER, SUCCESS, WARNING
    }

    public enum ButtonSize {
        SMALL, MEDIUM, LARGE
    }

    /**
     * Toggle version of RRButton retaining the same styling and behaviors.
     */
    public static class Toggle extends RRButton {
        private static final PseudoClass SELECTED_PSEUDO_CLASS = PseudoClass.getPseudoClass("selected");
        private final BooleanProperty selected = new BooleanPropertyBase(false) {
            @Override
            protected void invalidated() {
                pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, get());
            }

            @Override
            public Object getBean() {
                return Toggle.this;
            }

            @Override
            public String getName() {
                return "selected";
            }
        };

        public Toggle() {
            this("");
        }

        public Toggle(String localizationKey, Ikon icon, Object... args) {
            super(localizationKey, icon, args);
            initializeToggle();
        }

        public Toggle(String localizationKey, Node graphic, Object... args) {
            super(localizationKey, graphic, args);
            initializeToggle();
        }

        public Toggle(String localizationKey, Object... args) {
            super(localizationKey, args);
            initializeToggle();
        }

        public static Toggle primary(String text) {
            var button = new Toggle(text);
            button.setVariant(ButtonVariant.PRIMARY);
            return button;
        }

        public static Toggle secondary(String text) {
            var button = new Toggle(text);
            button.setVariant(ButtonVariant.SECONDARY);
            return button;
        }

        public static Toggle ghost(String text) {
            var button = new Toggle(text);
            button.setVariant(ButtonVariant.GHOST);
            return button;
        }

        public static Toggle danger(String text) {
            var button = new Toggle(text);
            button.setVariant(ButtonVariant.DANGER);
            return button;
        }

        public static Toggle success(String text) {
            var button = new Toggle(text);
            button.setVariant(ButtonVariant.SUCCESS);
            return button;
        }

        public static Toggle warning(String text) {
            var button = new Toggle(text);
            button.setVariant(ButtonVariant.WARNING);
            return button;
        }

        private void initializeToggle() {
            if (!getStyleClass().contains("toggle")) {
                getStyleClass().add("toggle");
            }
            addEventHandler(ActionEvent.ACTION, event -> {
                if (!isLoading()) {
                    setSelected(!isSelected());
                }
            });
        }

        public final boolean isSelected() {
            return selected.get();
        }

        public final void setSelected(boolean value) {
            selected.set(value);
        }

        public final BooleanProperty selectedProperty() {
            return selected;
        }
    }

    private static final class RRButtonBase<B extends ButtonBase> {
        private final B control;
        private ButtonVariant variant = ButtonVariant.PRIMARY;
        private ButtonSize size = ButtonSize.MEDIUM;
        private FontIcon icon;
        @Getter
        private boolean isLoading = false;
        private boolean square = false;
        private String localizationKey;
        private Object[] localizationArgs;

        private String originalText;
        private Node originalGraphic;
        private final FontIcon loadingSpinner;

        private RRButtonBase(B control) {
            this.control = control;
            loadingSpinner = new FontIcon(FontAwesomeSolid.SYNC_ALT);
            loadingSpinner.setIconSize(16);
            loadingSpinner.getStyleClass().add("loading-spinner");
        }

        private void initialize() {
            if (!control.getStyleClass().contains("rr-button")) {
                control.getStyleClass().add("rr-button");
            }

            control.setAlignment(Pos.CENTER);
            control.setPadding(new Insets(8, 16, 8, 16));

            control.setOnMousePressed($ -> {
                if (!isLoading) {
                    var scale = new ScaleTransition(Duration.millis(100), control);
                    scale.setToX(0.95);
                    scale.setToY(0.95);
                    scale.play();
                }
            });

            control.setOnMouseReleased($ -> {
                if (!isLoading) {
                    var scale = new ScaleTransition(Duration.millis(100), control);
                    scale.setToX(1.0);
                    scale.setToY(1.0);
                    scale.play();
                }
            });

            updateStyle();
            updateContent();
        }

        private void trackLocalization(String localizationKey, Object... args) {
            if (localizationKey != null && !localizationKey.isBlank()) {
                setLocalizedText(localizationKey, args);
            }
        }

        private void addLocalizationListener() {
            if (localizationKey != null) {
                ServiceLocator.getService(LocalizationService.class).currentLanguageProperty().addListener((observable, oldValue, newValue) -> {
                    if (!isLoading) {
                        control.setText(ServiceLocator.getService(LocalizationService.class).get(localizationKey, localizationArgs));
                    }
                });
            }
        }

        public void setLocalizedText(String localizationKey, Object... args) {
            this.localizationKey = localizationKey;
            this.localizationArgs = args;
            if (!isLoading) {
                control.setText(ServiceLocator.getService(LocalizationService.class).get(localizationKey, args));
            }

            addLocalizationListener();
        }

        public void setIcon(Ikon iconCode) {
            if (icon != null && control.getGraphic() == icon) {
                control.setGraphic(null);
            }

            if (iconCode != null) {
                icon = new FontIcon(iconCode);
                icon.setIconSize(16);
                icon.getStyleClass().add("button-icon");
            } else {
                icon = null;
            }

            if (!isLoading) {
                updateContent();
            }
        }

        public void setLoading(boolean loading) {
            if (this.isLoading == loading)
                return;

            this.isLoading = loading;

            if (loading) {
                originalText = control.getText();
                originalGraphic = control.getGraphic();

                control.setDisable(true);
                control.getStyleClass().add("loading");

                var loadingContent = new RRHBox(8);
                loadingContent.setAlignment(Pos.CENTER);
                loadingContent.getChildren().addAll(loadingSpinner);

                if (originalText != null && !originalText.isEmpty()) {
                    control.setText("Loading...");
                } else {
                    control.setText("");
                }

                control.setGraphic(loadingContent);
            } else {
                control.setDisable(false);
                control.getStyleClass().remove("loading");

                if (originalText != null) {
                    control.setText(originalText);
                }

                if (originalGraphic != null) {
                    control.setGraphic(originalGraphic);
                } else {
                    updateContent();
                }
            }
        }

        public void setRounded(boolean rounded) {
            if (rounded) {
                control.getStyleClass().add("rounded");
            } else {
                control.getStyleClass().remove("rounded");
            }
        }

        public void setSquare(boolean square) {
            if (this.square == square)
                return;

            this.square = square;

            if (square) {
                if (!control.getStyleClass().contains("square")) {
                    control.getStyleClass().add("square");
                }
            } else {
                control.getStyleClass().remove("square");
            }
        }

        public void setOutlined(boolean outlined) {
            if (outlined) {
                control.getStyleClass().add("outlined");
            } else {
                control.getStyleClass().remove("outlined");
            }
        }

        public void setFlat(boolean flat) {
            if (flat) {
                control.getStyleClass().add("flat");
            } else {
                control.getStyleClass().remove("flat");
            }
        }

        public void setVariant(ButtonVariant variant) {
            this.variant = variant;
            updateStyle();
        }

        public void setButtonSize(ButtonSize size) {
            this.size = size;
            updateStyle();
        }

        private void updateContent() {
            if (isLoading)
                return; // Don't update content while loading

            if (icon == null)
                return; // Preserve any custom graphic when no icon is set

            var content = new RRHBox(8);
            content.setAlignment(Pos.CENTER);
            content.getChildren().add(icon);

            if (control.getText() != null && !control.getText().isEmpty()) {
                control.setGraphic(content);
            } else {
                control.setGraphic(icon);
            }
        }

        private void updateStyle() {
            control.getStyleClass().removeAll("primary", "secondary", "ghost", "danger", "success", "warning");
            control.getStyleClass().removeAll("small", "medium", "large");

            switch (variant) {
                case PRIMARY -> control.getStyleClass().add("primary");
                case SECONDARY -> control.getStyleClass().add("secondary");
                case GHOST -> control.getStyleClass().add("ghost");
                case DANGER -> control.getStyleClass().add("danger");
                case SUCCESS -> control.getStyleClass().add("success");
                case WARNING -> control.getStyleClass().add("warning");
            }

            switch (size) {
                case SMALL -> control.getStyleClass().add("small");
                case MEDIUM -> control.getStyleClass().add("medium");
                case LARGE -> control.getStyleClass().add("large");
            }
        }
    }
}
