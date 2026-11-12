package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.SystemCheck;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Rows of the system check: tool, what it is for, state, and an "Kur" button that opens winget in a
 * console window (its prompts and licence are shown to the person, nothing is accepted silently).
 */
final class SystemCheckView extends VBox {
    private final AppContext ctx;
    private final Supplier<Path> project;
    private final Label summary = Ui.label("Denetleniyor…", "muted");

    SystemCheckView(AppContext ctx, Supplier<Path> project) {
        super(8);
        this.ctx = ctx;
        this.project = project;
        getChildren().add(summary);
    }

    /** Runs the checks in the background and redraws the rows. */
    void check() {
        summary.setText("Denetleniyor…");
        getChildren().setAll(summary);
        Path root = project.get();
        ctx.background.submit(() -> {
            List<SystemCheck.Item> items = SystemCheck.run(root);
            Platform.runLater(() -> show(items));
        });
    }

    private void show(List<SystemCheck.Item> items) {
        long missing = items.stream().filter(i -> !i.ok()).count();
        summary.setText(missing == 0 ? "Her şey hazır." : missing + " madde eksik ya da sorunlu. Eksik araç yalnızca kendi özelliğini etkiler.");
        getChildren().setAll(summary);
        for (SystemCheck.Item i : items) {
            String kind = switch (i.state()) {
                case OK -> "ok";
                case MISSING, OLD -> "warn";
                case PROBLEM -> "err";
            };
            String word = switch (i.state()) {
                case OK -> "hazır";
                case MISSING -> "yok";
                case OLD -> "eski";
                case PROBLEM -> "sorun";
            };
            VBox text = new VBox(2, Ui.label(i.name(), "strong"), Ui.wrapped(i.needed(), "small", "muted"));
            text.setPrefWidth(260);
            Label detail = Ui.wrapped(i.detail(), "small");
            HBox.setHgrow(detail, Priority.ALWAYS);
            detail.setMaxWidth(Double.MAX_VALUE);
            HBox row = new HBox(12, Ui.chip(word, kind), text, detail);
            row.setAlignment(Pos.CENTER_LEFT);
            if (!i.ok() && i.wingetId() != null) {
                Button install = Ui.button("Kur", "download", "small");
                install.setOnAction(e -> install(i));
                row.getChildren().add(install);
            }
            getChildren().add(row);
        }
        Button again = Ui.button("Yeniden denetle", "refresh", "small");
        again.setOnAction(e -> check());
        getChildren().add(again);
    }

    private void install(SystemCheck.Item i) {
        if (!Ui.confirm(ctx.stage, i.name() + " kurulsun mu?",
                "Bir komut penceresinde winget açılacak ve " + i.name() + " (" + i.wingetId() + ") kurulacak.\n"
                        + "Lisans ve yönetici izni sorulursa o pencerede sen onaylarsın.\n"
                        + "Kurulum bitince bu pencereye dönüp 'Yeniden denetle'ye bas; gerekirse uygulamayı yeniden başlat.",
                "Kurulumu başlat")) {
            return;
        }
        try {
            new ProcessBuilder(SystemCheck.installCommand(i.wingetId())).start();
            ctx.activity.note("ok", i.name() + " kurulumu başladı", "winget penceresinde adımları izle.");
        } catch (IOException | IllegalArgumentException ex) {
            ctx.activity.note("err", i.name() + " kurulumu başlatılamadı", ex.getMessage());
        }
    }
}
