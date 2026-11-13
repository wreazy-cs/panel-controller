package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Problem;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Small factories so every page builds buttons, cards and dialogs the same way. */
final class Ui {
    /** Buttons in the chosen language (created on use, after the language is set). */
    static ButtonType ok() {
        return new ButtonType(T.t("common.ok"), ButtonBar.ButtonData.OK_DONE);
    }

    static ButtonType cancel() {
        return new ButtonType(T.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    private Ui() {}

    static Button button(String text, String icon, String... styles) {
        Button b = new Button(text);
        b.getStyleClass().add("btn");
        b.getStyleClass().addAll(styles);
        if (icon != null) {
            b.setGraphic(Icons.of(icon, 16));
        }
        b.setMinWidth(Region.USE_PREF_SIZE); // never cut a button's text; long labels shrink instead
        return b;
    }

    static Button iconButton(String icon, String tooltip) {
        Button b = new Button();
        b.getStyleClass().addAll("btn", "ghost", "icon-only");
        b.setGraphic(Icons.of(icon, 18));
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    static Label label(String text, String... styles) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styles);
        return l;
    }

    static Label wrapped(String text, String... styles) {
        Label l = label(text, styles);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    /** Small rounded label; kind is ok, warn, err, info or "" (neutral). */
    static Label chip(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().add("chip");
        if (kind != null && !kind.isEmpty()) {
            l.getStyleClass().add(kind);
        }
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    static String kind(Problem.Severity s) {
        return switch (s) {
            case ERROR -> "err";
            case WARNING -> "warn";
            case INFO -> "info";
        };
    }

    static VBox card(Node... children) {
        VBox v = new VBox(12, children);
        v.getStyleClass().add("card");
        return v;
    }

    /** Title row of a card: bold title, muted description underneath, optional nodes on the right. */
    static Node cardHeader(String title, String description, Node... right) {
        VBox text = new VBox(2, label(title, "section-title"));
        if (description != null && !description.isEmpty()) {
            text.getChildren().add(wrapped(description, "muted"));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(8, text);
        row.getChildren().addAll(right);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    static VBox emptyState(String icon, String title, String text, Node... actions) {
        VBox v = new VBox(10, Icons.of(icon, 36, "muted"), label(title, "section-title"), wrapped(text, "muted"));
        v.getChildren().addAll(actions);
        v.getStyleClass().addAll("card", "empty-state");
        v.setAlignment(Pos.CENTER);
        return v;
    }

    /** 17.268 in Turkish, 17,268 in English. */
    static String number(long n) {
        return NumberFormat.getIntegerInstance(T.locale()).format(n);
    }

    /** %27 in Turkish, 27% in English. */
    static String percent(double p) {
        return T.t("fmt.percent", Math.round(p));
    }

    // ---------------------------------------------------------------- dialogs

    static void info(Window owner, String header, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ok());
        a.setHeaderText(header);
        a.setTitle("Panel Controller");
        a.initOwner(owner);
        a.showAndWait();
    }

    static boolean confirm(Window owner, String header, String text, String yes) {
        ButtonType go = new ButtonType(yes, ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, go, cancel());
        a.setHeaderText(header);
        a.setTitle("Panel Controller");
        a.initOwner(owner);
        return a.showAndWait().filter(b -> b == go).isPresent();
    }

    /** Dialog with several answers; returns the index of the chosen one, or -1 when closed. */
    static int choose(Window owner, String header, String text, List<String> answers) {
        Alert a = new Alert(Alert.AlertType.WARNING, text);
        a.getButtonTypes().clear();
        for (int i = 0; i < answers.size(); i++) {
            ButtonBar.ButtonData data = i == 0 ? ButtonBar.ButtonData.OK_DONE
                    : i == answers.size() - 1 ? ButtonBar.ButtonData.CANCEL_CLOSE : ButtonBar.ButtonData.OTHER;
            a.getButtonTypes().add(new ButtonType(answers.get(i), data));
        }
        a.setHeaderText(header);
        a.setTitle("Panel Controller");
        a.initOwner(owner);
        Optional<ButtonType> r = a.showAndWait();
        return r.map(b -> answers.indexOf(b.getText())).orElse(-1);
    }
}
