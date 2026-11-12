package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Backup;
import io.github.wreazycs.panelcontroller.ProjectStatus;
import io.github.wreazycs.panelcontroller.Slot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Geri dön: put an earlier live version back. The app copies; the person pastes into the panel. */
final class RestorePage implements Page {
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("dd.MM.yyyy  HH:mm");

    private final AppContext ctx;
    private final VBox view = new VBox(16);
    private final ListView<String> stamps = new ListView<>();
    private final VBox files = new VBox(8);

    RestorePage(AppContext ctx) {
        this.ctx = ctx;
        HBox banner = new HBox(12, Icons.of("restore", 20, "info"), Ui.wrapped(
                "Bir şey bozulduysa: yedekteki dosyayı panoya al, panelde aynı slota yapıştır ve kaydet. En üstteki yedek, "
                        + "en son yüklemeden hemen önceki canlı haldir. Bu işlem canlı sitede kendiliğinden değişiklik yapmaz."));
        banner.getStyleClass().addAll("banner", "info");

        stamps.setCellFactory(l -> new ListCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                VBox v = new VBox(2, Ui.label(pretty(s), "strong"), Ui.label(age(s), "small", "muted"));
                setGraphic(v);
                setText(null);
            }
        });
        stamps.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showFiles(b));
        stamps.setPrefWidth(240);
        stamps.setMinWidth(220);

        VBox left = Ui.card(Ui.cardHeader("Yedekler", "Yeniden eskiye"), stamps);
        VBox.setVgrow(stamps, Priority.ALWAYS);
        VBox right = Ui.card(Ui.cardHeader("Yedekteki dosyalar", "Panoya alınca panelde ilgili slota yapıştır."), files);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(16, left, right);
        VBox.setVgrow(body, Priority.ALWAYS);
        view.getChildren().addAll(banner, body);
        view.setPadding(new Insets(4, 24, 24, 24));
    }

    @Override public String id() { return "restore"; }
    @Override public String title() { return "Geri dön"; }
    @Override public String subtitle() { return "Canlı sitenin eski bir halini panele geri yapıştırmak için."; }
    @Override public String icon() { return "restore"; }
    @Override public Node view() { return view; }

    @Override
    public void refresh() {
        String selected = stamps.getSelectionModel().getSelectedItem();
        try {
            stamps.getItems().setAll(Backup.quiet(ctx.cfg).list());
        } catch (IOException e) {
            stamps.getItems().clear();
        }
        if (stamps.getItems().isEmpty()) {
            files.getChildren().setAll(Ui.wrapped("Henüz yedek yok. Yayın sayfasındaki Hazırla ya da Yedek al ile alınır.", "muted"));
            return;
        }
        if (selected != null && stamps.getItems().contains(selected)) {
            stamps.getSelectionModel().select(selected);
        } else {
            stamps.getSelectionModel().selectFirst();
        }
        showFiles(stamps.getSelectionModel().getSelectedItem());
    }

    private void showFiles(String stamp) {
        files.getChildren().clear();
        if (stamp == null) {
            return;
        }
        try {
            List<String> names = Backup.quiet(ctx.cfg).files(stamp);
            if (names.isEmpty()) {
                files.getChildren().add(Ui.label("Bu yedek boş.", "muted"));
            }
            for (String name : names) {
                Path p = ctx.cfg.backupDir().resolve(stamp).resolve(name);
                String slot = ctx.cfg.slots().stream().filter(s -> s.outputName().equals(name))
                        .map(Slot::panelName).findFirst().orElse("?");
                Button copy = ctx.guard(Ui.button("Panoya kopyala", "copy", "small"));
                copy.setOnAction(e -> copy(stamp, name, slot, p));
                VBox text = new VBox(2, Ui.label(name, "mono", "strong"),
                        Ui.label("Panel slotu: " + slot + "   ·   " + Ui.number(size(p)) + " karakter", "small", "muted"));
                HBox.setHgrow(text, Priority.ALWAYS);
                HBox row = new HBox(10, Icons.of("notes", 18, "muted"), text, copy);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6, 0, 6, 0));
                files.getChildren().add(row);
            }
        } catch (IOException e) {
            files.getChildren().add(Ui.label("Okunamadı: " + e.getMessage(), "error-text"));
        }
    }

    private void copy(String stamp, String name, String slot, Path file) {
        if (!Ui.confirm(ctx.stage, name + " yedeği panoya kopyalansın mı?",
                "Yedek: " + pretty(stamp) + "\nCanlı sitede değişiklik yapmaz; yapıştırmayı sen yapacaksın.", "Kopyala")) {
            return;
        }
        try {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(Files.readString(file, StandardCharsets.UTF_8));
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            ctx.activity.note("ok", "Yedekten panoya: " + name,
                    pretty(stamp) + " yedeği. Panelde '" + slot + "' slotunu aç, içeriği sil, Ctrl+V, kaydet. "
                            + "Sonra Yayın sayfasında Canlıyla karşılaştır.");
        } catch (IOException e) {
            ctx.activity.note("err", "Panoya kopyalanamadı", e.getMessage());
        }
    }

    private static int size(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).length();
        } catch (IOException e) {
            return 0;
        }
    }

    static String pretty(String stamp) {
        try {
            return LocalDateTime.parse(stamp, Backup.STAMP).format(SHOW);
        } catch (DateTimeParseException e) {
            return stamp;
        }
    }

    private static String age(String stamp) {
        try {
            Duration d = Duration.between(LocalDateTime.parse(stamp, Backup.STAMP), LocalDateTime.now());
            return ProjectStatus.describeAge(d);
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    static Optional<String> latest(AppContext ctx) {
        try {
            return Backup.quiet(ctx.cfg).list().stream().findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
