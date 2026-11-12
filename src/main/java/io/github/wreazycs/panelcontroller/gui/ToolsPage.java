package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.DevServer;
import io.github.wreazycs.panelcontroller.PagePreview;
import io.github.wreazycs.panelcontroller.SessionNotes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Araçlar: page preview, local test bench, session notes. */
final class ToolsPage implements Page {
    private final AppContext ctx;
    private final ScrollPane view;
    private final ComboBox<Path> pages = new ComboBox<>();
    private final Label serverState = Ui.chip("kapalı", "");
    private final Label benchHint = Ui.wrapped("", "hint");
    private final TextField noteName = new TextField("samet");
    private final VBox notes = new VBox(6);
    private DevServer server;

    ToolsPage(AppContext ctx) {
        this.ctx = ctx;

        pages.setCellFactory(l -> new ListCell<>() {
            @Override protected void updateItem(Path p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.getFileName().toString());
            }
        });
        pages.setButtonCell(pages.getCellFactory().call(null));
        pages.setPrefWidth(360);
        Button preview = Ui.button("Tarayıcıda önizle", "eye", "primary");
        preview.setOnAction(e -> preview());
        VBox previewCard = Ui.card(Ui.cardHeader("Sayfa önizleme",
                        "veri/sayfalar/ altındaki panel sayfası içeriklerini (KVKK, kartla ödeme gibi) tarayıcıda gösterir. "
                                + "Panele yapıştırılan dosya her zaman ham dosyadır."),
                new HBox(8, pages, preview));

        Button start = Ui.button("Başlat ve aç", "play", "primary");
        start.setOnAction(e -> startBench());
        Button stop = Ui.button("Durdur", "stop");
        stop.setOnAction(e -> stopBench());
        HBox benchRow = new HBox(8, start, stop, Ui.spacer(), Ui.label("Sunucu:", "muted"), serverState);
        benchRow.setAlignment(Pos.CENTER_LEFT);
        VBox benchCard = Ui.card(Ui.cardHeader("Test tezgahı",
                        "Proje klasörünü yalnızca bu bilgisayara (127.0.0.1:" + DevServer.PORT + ") açar ve projenin tezgah sayfasını "
                                + "üretip tarayıcıda gösterir. Tezgah KISMİDİR: tüm siteyi değil, projenin betiğinde yazılı parçaları "
                                + "gösterir. Tüm site için canlı sayfayı kullan."),
                benchRow, benchHint);

        noteName.setPrefColumnCount(12);
        Button create = Ui.button("Oluştur ve aç", "pen", "primary");
        create.setOnAction(e -> openNote());
        HBox noteRow = new HBox(8, Ui.label("Ad:"), noteName, create);
        noteRow.setAlignment(Pos.CENTER_LEFT);
        VBox noteCard = Ui.card(Ui.cardHeader("Oturum notu",
                        "veri/oturumlar/ altında bugünün notunu başlangıç saatiyle açar (git dışı). Oturumun ilk işi; bitirirken "
                                + "ne yapıldığını, yüklenip yüklenmediğini ve nasıl geri alınacağını yaz."),
                noteRow, Ui.label("Son notlar", "strong"), notes);

        VBox content = new VBox(16, previewCard, benchCard, noteCard);
        content.setPadding(new Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
    }

    @Override public String id() { return "tools"; }
    @Override public String title() { return "Araçlar"; }
    @Override public String subtitle() { return "Sayfa önizleme, yerel test tezgahı ve oturum notu."; }
    @Override public String icon() { return "tools"; }
    @Override public Node view() { return view; }

    @Override
    public void refresh() {
        Path selected = pages.getValue();
        try {
            pages.getItems().setAll(PagePreview.pages(ctx.cfg.root()));
        } catch (IOException e) {
            pages.getItems().clear();
        }
        if (selected != null && pages.getItems().contains(selected)) {
            pages.setValue(selected);
        } else if (!pages.getItems().isEmpty()) {
            pages.setValue(pages.getItems().get(0));
        }
        pages.setPromptText(pages.getItems().isEmpty() ? "veri/sayfalar/ boş" : "Sayfa seç");

        boolean running = server != null && server.running();
        serverState.setText(running ? "çalışıyor" : "kapalı");
        serverState.getStyleClass().removeAll("ok");
        if (running) serverState.getStyleClass().add("ok");
        benchHint.setText(Files.isRegularFile(DevServer.benchScript(ctx.cfg.root()))
                ? "Tezgah sayfası projenin arac/tezgah-yap.py betiğiyle üretilir (Python gerekir)."
                : "Bu projede tezgah betiği (arac/tezgah-yap.py) yok; yalnızca dosya sunucusu açılır.");

        notes.getChildren().clear();
        try {
            List<Path> all = SessionNotes.list(ctx.cfg.root());
            if (all.isEmpty()) {
                notes.getChildren().add(Ui.label("Henüz not yok.", "muted"));
            }
            for (Path p : all.stream().limit(5).toList()) {
                Button open = Ui.button("Aç", "external", "small", "ghost");
                open.setOnAction(e -> ctx.open(p));
                Label name = Ui.label(p.getFileName().toString(), "mono", "small");
                HBox.setHgrow(name, Priority.ALWAYS);
                HBox row = new HBox(8, Icons.of("notes", 16, "muted"), name, Ui.spacer(), open);
                row.setAlignment(Pos.CENTER_LEFT);
                notes.getChildren().add(row);
            }
        } catch (IOException e) {
            notes.getChildren().add(Ui.label("Okunamadı: " + e.getMessage(), "error-text"));
        }
    }

    private void preview() {
        Path page = pages.getValue();
        if (page == null) {
            return;
        }
        try {
            Path out = PagePreview.build(ctx.cfg.root(), page);
            ctx.open(out);
            ctx.activity.note("ok", "Önizleme açıldı: " + page.getFileName(), ctx.cfg.rel(out));
        } catch (IOException | IllegalArgumentException e) {
            ctx.activity.note("err", "Önizleme açılamadı", e.getMessage());
        }
    }

    private void startBench() {
        Path root = ctx.cfg.root();
        if (server == null || !server.root().equals(root)) {
            if (server != null) server.stop();
            server = new DevServer(root);
        }
        DevServer srv = server;
        ctx.run("Test tezgahı", out -> {
            srv.start(DevServer.PORT);
            if (!Files.isRegularFile(DevServer.benchScript(root))) {
                return srv.url("");
            }
            out.println(DevServer.generateBench(root));
            return srv.url(ctx.cfg.rel(DevServer.benchPage(root)));
        }, (e, url) -> {
            ctx.openUrl(url);
            e.done("ok", "Açıldı: " + url + ". Bitince Durdur.", List.of());
        });
    }

    private void stopBench() {
        if (server != null) {
            server.stop();
            ctx.activity.note("ok", "Test tezgahı durduruldu", "Yerel sunucu kapatıldı.");
        }
        refresh();
    }

    /** Called when the app closes. */
    void shutdown() {
        if (server != null) {
            server.stop();
        }
    }

    private void openNote() {
        try {
            SessionNotes.Opened o = SessionNotes.open(ctx.cfg.root(), noteName.getText(), LocalDateTime.now());
            ctx.open(o.file());
            ctx.activity.note("ok", o.created() ? "Oturum notu açıldı" : "Bugünün notu zaten var, açıldı", ctx.cfg.rel(o.file()));
            refresh();
        } catch (IOException | IllegalArgumentException e) {
            ctx.activity.note("err", "Oturum notu açılamadı", e.getMessage());
        }
    }
}
