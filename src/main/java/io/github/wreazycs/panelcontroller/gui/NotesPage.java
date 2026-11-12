package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.AppInfo;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Sürüm notları: what the app is for, and what each version added, changed and fixed. */
final class NotesPage implements Page {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final ScrollPane view;

    NotesPage() {
        VBox content = new VBox(16);
        VBox purpose = Ui.card(Ui.cardHeader("Temel amaç", null));
        for (String p : AppInfo.PURPOSE) {
            purpose.getChildren().add(Ui.wrapped(p));
        }
        purpose.getChildren().add(Ui.wrapped("Sürümleme: Semantik Sürümleme (0.MINOR.PATCH). Uygulama beta olduğu için 0 ile başlıyor; "
                + "yeni özellik MINOR'ı, yalnızca hata düzeltmesi PATCH'i artırır.", "muted"));
        content.getChildren().add(purpose);

        for (AppInfo.Release r : AppInfo.RELEASES) {
            HBox head = new HBox(10, Ui.chip("v" + r.version(), r.version().equals(AppInfo.VERSION) ? "info" : ""),
                    Ui.label(r.title(), "section-title"), Ui.spacer(), Ui.label(r.date().format(DAY), "muted"));
            head.setAlignment(Pos.CENTER_LEFT);
            VBox card = Ui.card(head);
            section(card, "Eklendi", "ok", r.added());
            section(card, "Değişti", "info", r.changed());
            section(card, "Düzeltildi", "warn", r.fixed());
            content.getChildren().add(card);
        }

        VBox older = Ui.card(Ui.cardHeader("Önceki: Panel Kumanda (Python)",
                "Panel Controller, IC Elektronik deposundaki Python araçlarının ve Panel Kumanda penceresinin devamıdır."));
        for (String s : AppInfo.PREDECESSOR) {
            older.getChildren().add(bullet(s));
        }
        content.getChildren().add(older);

        content.setPadding(new Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
    }

    private static void section(VBox card, String title, String kind, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        VBox v = new VBox(6, Ui.chip(title, kind));
        for (String s : items) {
            v.getChildren().add(bullet(s));
        }
        card.getChildren().add(v);
    }

    private static Node bullet(String text) {
        HBox h = new HBox(8, Ui.label("•", "muted"), Ui.wrapped(text));
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    @Override public String id() { return "notes"; }
    @Override public String title() { return "Sürüm notları"; }
    @Override public String subtitle() { return "Uygulamanın amacı ve her sürümde ne değişti."; }
    @Override public String icon() { return "notes"; }
    @Override public Node view() { return view; }
    @Override public void refresh() { }
}
