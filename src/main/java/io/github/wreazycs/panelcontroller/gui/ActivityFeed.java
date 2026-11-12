package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Finding;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * "İşlemler": one card per operation instead of a scrolling log. A card shows the result in one
 * line, the problems with their codes (click to read the explanation in the guide) and, folded
 * away, the full output.
 */
final class ActivityFeed {
    private static final int MAX_CARDS = 40;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final VBox list = new VBox();
    private final BorderPane view = new BorderPane();
    private final Consumer<String> openGuide;
    private final Label emptyHint = Ui.wrapped("Yaptığın işlemlerin sonucu burada görünür.", "muted");

    ActivityFeed(Consumer<String> openGuide) {
        this.openGuide = openGuide;
        list.getStyleClass().add("activity-list");
        list.getChildren().add(emptyHint);

        Button clear = Ui.button("Temizle", null, "ghost", "small");
        clear.setOnAction(e -> {
            list.getChildren().setAll(emptyHint);
        });
        HBox header = new HBox(8, Icons.of("activity", 18), Ui.label("İşlemler", "section-title"), Ui.spacer(), clear);
        header.getStyleClass().add("activity-header");

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        view.setTop(header);
        view.setCenter(scroll);
        view.getStyleClass().add("activity");
        view.setPrefWidth(360);
        view.setMinWidth(300);
    }

    Node view() {
        return view;
    }

    /** A card in the "running" state; finish it with {@link Entry#done}. */
    Entry start(String title) {
        Entry e = new Entry(title);
        list.getChildren().remove(emptyHint);
        list.getChildren().add(0, e.card);
        if (list.getChildren().size() > MAX_CARDS) {
            list.getChildren().remove(MAX_CARDS, list.getChildren().size());
        }
        return e;
    }

    /** A finished card in one call (for quick messages). */
    void note(String kind, String title, String summary) {
        start(title).done(kind, summary, List.of());
    }

    final class Entry {
        final VBox card = new VBox();
        private final StackPane icon = new StackPane();
        private final Label summary = Ui.wrapped("Sürüyor…", "muted");
        private final VBox findings = new VBox(4);
        private String raw = "";

        Entry(String title) {
            ProgressIndicator spin = new ProgressIndicator();
            spin.setPrefSize(16, 16);
            spin.setMaxSize(16, 16);
            icon.getChildren().add(spin);
            icon.setMinSize(18, 18);
            icon.setAlignment(Pos.TOP_CENTER);

            Label t = Ui.label(title, "activity-title");
            Label time = Ui.label(LocalTime.now().format(TIME), "activity-time");
            HBox top = new HBox(8, t, Ui.spacer(), time);
            top.setAlignment(Pos.CENTER_LEFT);
            VBox body = new VBox(4, top, summary, findings);
            HBox.setHgrow(body, Priority.ALWAYS);
            HBox row = new HBox(10, icon, body);
            card.getChildren().add(row);
            card.getStyleClass().addAll("activity-card", "running");
        }

        void setRaw(String text) {
            raw = text == null ? "" : text.strip();
        }

        /** kind: ok, warn or err. */
        void done(String kind, String text, List<Finding> problems) {
            card.getStyleClass().removeAll("running", "ok", "warn", "err");
            card.getStyleClass().add(kind);
            icon.getChildren().setAll(Icons.of(switch (kind) {
                case "ok" -> "check";
                case "err" -> "x";
                default -> "alert";
            }, 16, kind));
            summary.setText(text);
            summary.getStyleClass().remove("muted");
            for (Finding f : problems) {
                findings.getChildren().add(findingView(f));
            }
            if (!raw.isEmpty()) {
                TextArea area = new TextArea(raw);
                area.setEditable(false);
                area.setWrapText(false);
                area.setPrefRowCount(Math.min(14, (int) raw.lines().count() + 1));
                area.getStyleClass().add("raw-output");
                TitledPane details = new TitledPane("Ayrıntılı çıktı", area);
                details.setExpanded(false);
                details.setAnimated(false);
                details.getStyleClass().add("details");
                card.getChildren().add(details);
            }
        }

        private Node findingView(Finding f) {
            Label code = Ui.chip(f.problem().code(), Ui.kind(f.problem().severity()));
            code.getStyleClass().add("link");
            code.setOnMouseClicked(e -> openGuide.accept(f.problem().code()));
            code.setTooltip(new javafx.scene.control.Tooltip("Kılavuzda aç: " + f.problem().title()));
            HBox head = new HBox(6, code, Ui.label(f.problem().title(), "strong"));
            head.setAlignment(Pos.CENTER_LEFT);
            VBox v = new VBox(2, head);
            if (!f.where().isEmpty()) {
                v.getChildren().add(Ui.label(f.where(), "mono", "small", "muted"));
            }
            if (!f.detail().isEmpty() && !f.detail().equals(f.problem().title())) {
                v.getChildren().add(Ui.wrapped(f.detail(), "small"));
            }
            v.getChildren().add(Ui.wrapped("Ne yapmalı: " + f.problem().fix(), "finding-fix"));
            v.getStyleClass().add("finding");
            return v;
        }
    }
}
