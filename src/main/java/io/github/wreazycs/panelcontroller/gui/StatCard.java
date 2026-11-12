package io.github.wreazycs.panelcontroller.gui;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Small status card: title, coloured value, one line of detail. */
final class StatCard extends VBox {
    private final Label value = Ui.label("-", "stat-value");
    private final Label sub = Ui.label("", "stat-sub");

    StatCard(String title) {
        // ROOT, not Turkish: "Git" must read GIT, not GİT ("ı" still becomes I)
        getChildren().addAll(Ui.label(title.toUpperCase(java.util.Locale.ROOT), "stat-title"), value, sub);
        getStyleClass().add("stat");
        sub.setWrapText(true);
        HBox.setHgrow(this, Priority.ALWAYS);
        setMaxWidth(Double.MAX_VALUE);
        setPrefWidth(200);
    }

    /** kind: ok, warn, err, info or "" (neutral). */
    void set(String kind, String text, String detail) {
        getStyleClass().removeAll("ok", "warn", "err", "info");
        if (kind != null && !kind.isEmpty()) {
            getStyleClass().add(kind);
        }
        value.setText(text);
        sub.setText(detail == null ? "" : detail);
    }
}
