package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Tasks;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Görevler: the project's tasks/ folder, grouped by state. */
final class TasksPage implements Page {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AppContext ctx;
    private final ScrollPane view;
    private final VBox list = new VBox(10);
    private final CheckBox showAll = new CheckBox("Biten ve iptal edilenleri de göster");

    TasksPage(AppContext ctx) {
        this.ctx = ctx;
        showAll.setOnAction(e -> refresh());
        showAll.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox content = new VBox(16, Ui.card(Ui.cardHeader("Görevler",
                "tasks/ klasöründeki dosyalar. Dosya adı tarihi ve durumu söyler; bir görevi açmak için Aç'a bas.", showAll)), list);
        content.setPadding(new Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
    }

    @Override public String id() { return "tasks"; }
    @Override public String title() { return "Görevler"; }
    @Override public String subtitle() { return "Yapılacak, süren ve bekleyen işler."; }
    @Override public String icon() { return "tasks"; }
    @Override public Node view() { return view; }

    @Override
    public void refresh() {
        list.getChildren().clear();
        List<Tasks.Task> tasks;
        try {
            tasks = Tasks.read(ctx.cfg.root());
        } catch (IOException e) {
            list.getChildren().add(Ui.label("tasks/ okunamadı: " + e.getMessage(), "error-text"));
            return;
        }
        if (tasks.isEmpty()) {
            list.getChildren().add(Ui.emptyState("tasks", "Görev yok", "Bu projede tasks/ klasörü yok ya da boş."));
            return;
        }
        Tasks.Status group = null;
        int hidden = 0;
        for (Tasks.Task t : tasks) {
            if (!showAll.isSelected() && !t.status().active()) {
                hidden++;
                continue;
            }
            if (t.status() != group) {
                group = t.status();
                list.getChildren().add(Ui.label(group.label, "section-title"));
            }
            list.getChildren().add(card(t));
        }
        if (hidden > 0) {
            list.getChildren().add(Ui.label(hidden + " bitmiş ya da iptal edilmiş görev gizli.", "muted"));
        }
    }

    private Node card(Tasks.Task t) {
        String kind = switch (t.status()) {
            case IN_PROGRESS -> "info";
            case ON_HOLD -> "warn";
            case DONE -> "ok";
            case CANCELLED, UNKNOWN -> "";
            case TODO -> "";
        };
        String dates = t.start() == null ? "" : t.start().format(DAY) + (t.end() == null ? "" : " – " + t.end().format(DAY));
        HBox meta = new HBox(10, Ui.chip(t.status().label, kind), Ui.label(dates, "small", "muted"));
        if (!t.priority().isEmpty()) {
            meta.getChildren().add(Ui.chip(t.priority(), ""));
        }
        if (t.checksTotal() > 0) {
            ProgressBar bar = new ProgressBar((double) t.checksDone() / t.checksTotal());
            bar.setPrefWidth(80);
            meta.getChildren().addAll(bar, Ui.label(t.checksDone() + "/" + t.checksTotal() + " madde", "small", "muted"));
        }
        meta.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(6, Ui.wrapped(t.title(), "strong"), meta);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button open = Ui.button("Aç", "external", "small");
        open.setOnAction(e -> ctx.open(t.file()));
        HBox row = new HBox(12, text, open);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("card");
        row.setPadding(new Insets(12, 16, 12, 16));
        return row;
    }
}
