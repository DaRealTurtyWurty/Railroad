package dev.railroadide.railroad.ide.projectexplorer.dialog;

import dev.railroadide.railroad.AppResources;
import dev.railroadide.railroad.Railroad;
import dev.railroadide.railroad.Services;
import dev.railroadide.railroad.ide.language.jvm.JvmProjectResolver;
import dev.railroadide.railroad.ide.language.jvm.JvmSourceSets;
import dev.railroadide.railroad.ide.projectexplorer.FileCreateType;
import dev.railroadide.railroad.ide.sst.document.api.DocumentStore;
import dev.railroadide.railroad.ide.sst.document.api.WorkspaceSnapshot;
import dev.railroadide.railroad.plugin.spi.dto.Project;
import dev.railroadide.railroad.ui.RRButton;
import dev.railroadide.railroad.ui.RRGridPane;
import dev.railroadide.railroad.ui.RRListView;
import dev.railroadide.railroad.ui.localized.LocalizedLabel;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import lombok.Getter;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.StringJoiner;

// TODO: Refactor this to use WindowBuilder(?)
/**
 * Creates files or directories from the user's selected type and name.
 */
public class CreateFileDialog {
    /**
     * Shows a modal creation dialog owned by the supplied window.
     *
     * @param owner window owning the creation dialog
     * @param path filesystem path to operate on
     * @param type kind of file or directory to create
     */
    public static void open(Window owner, Path path, FileCreateType type) {
        var dialog = new Stage(StageStyle.UTILITY);
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(switch (type) {
            case FILE -> "railroad.dialog.create_file.title.file";
            case FOLDER -> "railroad.dialog.create_file.title.folder";
            case JAVA_CLASS -> "railroad.dialog.create_file.title.java_class";
            case JSON -> "railroad.dialog.create_file.title.json";
            case TXT -> "railroad.dialog.create_file.title.txt";
        });

        var root = new RRGridPane();
        root.getStyleClass().add("create-file-dialog");

        var title = new LocalizedLabel(switch (type) {
            case FILE -> "railroad.dialog.create_file.title.file";
            case FOLDER -> "railroad.dialog.create_file.title.folder";
            case JAVA_CLASS -> "railroad.dialog.create_file.title.java_class";
            case JSON -> "railroad.dialog.create_file.title.json";
            case TXT -> "railroad.dialog.create_file.title.txt";
        });

        var textField = new TextField();
        textField.setText(switch (type) {
            case FOLDER -> path.resolve("New Folder").toString();
            default -> "";
        });

        var listView = new RRListView<TypeSelection>();
        listView.setCellFactory(_ -> new TypeSelectorCell());
        listView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                textField.setText(switch (type) {
                    case FOLDER -> path.resolve("New Folder").toString();
                    default -> "";
                });
            }
        });

        listView.getItems().addAll(switch (type) {
            case JAVA_CLASS ->
                new TypeSelection[]{TypeSelection.JAVA_CLASS, TypeSelection.JAVA_INTERFACE, TypeSelection.JAVA_ENUM,
                    TypeSelection.JAVA_ANNOTATION};

            case JSON -> new TypeSelection[]{TypeSelection.JSON};

            case TXT -> new TypeSelection[]{TypeSelection.TXT};

            default -> new TypeSelection[0];
        });

        listView.getSelectionModel().selectFirst();

        var okButton = new RRButton("railroad.generic.ok");
        okButton.setOnAction(_ -> {
            TypeSelection selectedItem = listView.getSelectionModel().getSelectedItem();
            try {
                String name = textField.getText();
                if (type == FileCreateType.FOLDER) {
                    Files.createDirectories(path.resolve(name));
                    dialog.hide();
                    return;
                }

                Path file;
                if (selectedItem == null) {
                    file = Files.createFile(path.resolve(name));
                } else {
                    file = path.resolve(name + "." + selectedItem.getExtension());
                    Files.createDirectories(file.getParent());
                    String content = selectedItem.getTemplate();
                    if (type == FileCreateType.JAVA_CLASS) {
                        String className = file.getFileName().toString();
                        className = className.substring(0, className.length() - ".java".length());
                        content = packageDeclaration(file.getParent()) + content.replace("<class_name>", className);
                    }

                    Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                }

                dialog.hide();
                Services.EDITOR_TAB_MANAGER.openAndFocus(file);
            } catch (IOException | InvalidPathException exception) {
                Railroad.LOGGER.error("Failed to create entry in {}", path, exception);
            }
        });

        var cancelButton = new RRButton("railroad.generic.cancel");
        cancelButton.setOnAction(event -> dialog.hide());

        root.add(title, 0, 0, 2, 1);
        root.add(textField, 0, 1, 2, 1);
        if (!listView.getItems().isEmpty()) {
            root.add(listView, 0, 2, 2, 1);
            root.addRow(3, okButton, cancelButton);
        } else {
            root.addRow(2, okButton, cancelButton);
        }

        var scene = new Scene(root);
        var stylesheet = AppResources.getResource("styles/components/dialog.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private static String packageDeclaration(Path directory) {
        Optional<Path> relative = Services.IDE_STATE.getDocumentStore().map(DocumentStore::capture)
            .map(WorkspaceSnapshot::configuration)
            .flatMap(configuration -> JvmSourceSets.relativeSourcePath(configuration, directory));
        if (relative.isEmpty()) {
            Project project = Services.IDE_STATE.getCurrentProject();
            if (project != null) {
                Path normalized = JvmSourceSets.lookupPath(directory);
                relative = JvmProjectResolver.conventionalSourceRoots(project.getPath()).stream()
                    .map(JvmSourceSets::lookupPath)
                    .filter(normalized::startsWith)
                    .findFirst()
                    .map(root -> root.relativize(normalized));
            }
        }

        if (relative.isEmpty() || relative.get().toString().isEmpty())
            return "";

        var name = new StringJoiner(".");
        for (Path folder : relative.get()) {
            String part = folder.toString();
            if (!SourceVersion.isIdentifier(part) || SourceVersion.isKeyword(part))
                return "";

            name.add(part);
        }

        return "package " + name + ";\n\n";
    }

    /**
     * File templates available in the creation dialog.
     */
    @Getter
    public enum TypeSelection {
        /**
         * Creates a Java class source file.
         */
        JAVA_CLASS("Java Class", "java", null, """
            public class <class_name> {
                public <class_name>() {

                }
            }
            """),
        /**
         * Creates a Java interface source file.
         */
        JAVA_INTERFACE("Java Interface", "java", null, """
            public interface <class_name> {

            }
            """),
        /**
         * Creates a Java enum source file.
         */
        JAVA_ENUM("Java Enum", "java", null, """
            public enum <class_name> {

            }
            """),
        /**
         * Creates a Java annotation type source file.
         */
        JAVA_ANNOTATION("Java Annotation", "java", null, """
            public @interface <class_name> {

            }
            """),
        /**
         * Creates a JSON file.
         */
        JSON("JSON File", "json", null, """
            {

            }
            """),
        /**
         * Creates a plain text file.
         */
        TXT("Text File", "txt", null, "");

        private final String name;
        private final String extension;
        private final ImageView icon;
        private final String template;

        TypeSelection(String name, String extension, Image icon, String template) {
            this.name = name;
            this.extension = extension;
            this.icon = new ImageView(icon);
            this.template = template;
        }
    }

    /**
     * Displays a file template with its label and icon.
     */
    public static class TypeSelectorCell extends ListCell<TypeSelection> {
        @Override
        protected void updateItem(TypeSelection item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
            } else {
                setText(item.getName());
                setGraphic(item.getIcon());
            }
        }
    }
}
