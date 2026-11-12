package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Cdn;
import io.github.wreazycs.panelcontroller.Slot;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

/** Ayarlar: project folder and store settings, theme, API key and PIN. */
final class SettingsPage implements Page {
    private final AppContext ctx;
    private final VaultUi vaultUi;
    private final ScrollPane view;

    private final Label folder = Ui.label("", "mono");
    private final TextField cdn = new TextField();
    private final TextField slotTarget = numberField();
    private final TextField budget = numberField();
    private final Label cdnResult = Ui.wrapped("", "small");
    private final Label keyState = Ui.label("", "strong");
    private final Button changePin;
    private final Button removePin;
    private final Button lock;
    private final ToggleButton light = new ToggleButton("Açık");
    private final ToggleButton dark = new ToggleButton("Koyu");
    private Supplier<String> theme;

    SettingsPage(AppContext ctx, VaultUi vaultUi, Consumer<Path> projectChooser, Consumer<String> themeSetter,
                 Supplier<String> theme, Runnable lockNow) {
        this.ctx = ctx;
        this.vaultUi = vaultUi;

        // ---- project
        Button choose = Ui.button("Klasör seç…", "folder");
        choose.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Proje klasörü (kaynak/ klasörünü içeren)");
            if (Files.isDirectory(ctx.cfg.root())) dc.setInitialDirectory(ctx.cfg.root().toFile());
            File f = dc.showDialog(ctx.stage);
            if (f != null) projectChooser.accept(f.toPath());
        });
        folder.setMinWidth(0);
        HBox.setHgrow(folder, Priority.ALWAYS);
        folder.setMaxWidth(Double.MAX_VALUE);
        HBox folderRow = new HBox(10, folder, choose);
        folderRow.setAlignment(Pos.CENTER_LEFT);

        cdn.setPromptText("https://platincdn.com/<mağaza no>/tema/%s");
        HBox.setHgrow(cdn, Priority.ALWAYS);
        Button test = Ui.button("Bağlantıyı dene", "compare");
        test.setOnAction(e -> testCdn());
        Button save = Ui.button("Kaydet", "check", "primary");
        save.setOnAction(e -> save());

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.add(Ui.label("Mağaza CDN adresi", "strong"), 0, 0);
        grid.add(new HBox(8, cdn, test), 1, 0);
        grid.add(Ui.wrapped("Canlı tema dosyalarının adresi; %s dosya adının (genel.css ...) yerine geçer. Yedek ve canlı "
                + "karşılaştırma bunu kullanır. Mağazanın sitesinde tarayıcıda Kaynağı Görüntüle ile tema/genel.css adresini bulabilirsin.",
                "hint"), 1, 1);
        grid.add(cdnResult, 1, 2);
        grid.add(Ui.label("Slot hedefi", "strong"), 0, 3);
        grid.add(new HBox(8, slotTarget, Ui.label("karakter (aşılırsa uyarı)", "muted")), 1, 3);
        grid.add(Ui.label("Toplam bütçe", "strong"), 0, 4);
        grid.add(new HBox(8, budget, Ui.label("karakter (aşılırsa derleme durur)", "muted")), 1, 4);
        javafx.scene.layout.ColumnConstraints labels = new javafx.scene.layout.ColumnConstraints();
        labels.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints fields = new javafx.scene.layout.ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, fields);
        VBox project = Ui.card(Ui.cardHeader("Proje",
                        "Bu ayarlar her proje klasörü için ayrı saklanır; ayrı bir ayar dosyası gerekmez."),
                folderRow, grid, new HBox(save));

        // ---- appearance
        ToggleGroup g = new ToggleGroup();
        for (ToggleButton b : new ToggleButton[] {light, dark}) {
            b.setToggleGroup(g);
            b.getStyleClass().add("btn");
        }
        light.setGraphic(Icons.of("sun", 16));
        dark.setGraphic(Icons.of("moon", 16));
        light.setOnAction(e -> { light.setSelected(true); themeSetter.accept("light"); });
        dark.setOnAction(e -> { dark.setSelected(true); themeSetter.accept("dark"); });
        this.theme = theme;
        (theme.get().equals("dark") ? dark : light).setSelected(true);
        VBox appearance = Ui.card(Ui.cardHeader("Görünüm", "Tema hemen değişir ve hatırlanır."), new HBox(8, light, dark));

        // ---- key and PIN
        Button key = Ui.button("Anahtarı gir / değiştir…", "key", "primary");
        key.setOnAction(e -> vaultUi.keyDialog());
        changePin = Ui.button("PIN'i değiştir…", "pen");
        changePin.setOnAction(e -> vaultUi.changePinDialog());
        removePin = Ui.button("PIN'i kaldır…", "x", "danger");
        removePin.setOnAction(e -> vaultUi.removePinDialog());
        lock = Ui.button("Şimdi kilitle", "lock");
        lock.setOnAction(e -> lockNow.run());
        javafx.scene.layout.FlowPane keyButtons = new javafx.scene.layout.FlowPane(8, 8, key, changePin, removePin, lock);
        VBox keys = Ui.card(Ui.cardHeader("API anahtarı ve PIN",
                        "Anahtar ekrana, günlüğe ya da panoya yazılmaz. 'Bu bilgisayarda hatırla' seçilirse uygulama her açılışta "
                                + "4 haneli PIN sorar; 5 yanlış girişte kayıtlı anahtar silinir."),
                keyState, keyButtons);

        // ---- files
        Button errors = Ui.button("Hata günlüğünü aç", "notes");
        errors.setOnAction(e -> {
            if (Files.exists(ErrorLog.file())) {
                ctx.open(ErrorLog.file());
            } else {
                Ui.info(ctx.stage, "Hata günlüğü boş", "Henüz beklenmeyen bir hata kaydedilmedi.");
            }
        });
        VBox files = Ui.card(Ui.cardHeader("Uygulama dosyaları",
                        "Kayıtlı anahtar ve hata günlüğü " + ErrorLog.file().getParent() + " altında durur (OneDrive ve git dışında)."),
                new HBox(errors));

        VBox content = new VBox(16, project, appearance, keys, files);
        content.setPadding(new Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
    }

    private static TextField numberField() {
        TextField f = new TextField();
        f.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,7}") ? c : null));
        f.setPrefColumnCount(8);
        return f;
    }

    @Override public String id() { return "settings"; }
    @Override public String title() { return "Ayarlar"; }
    @Override public String subtitle() { return "Proje, görünüm ve API anahtarı."; }
    @Override public String icon() { return "settings"; }
    @Override public Node view() { return view; }

    @Override
    public void refresh() {
        folder.setText(ctx.settings.root.toString());
        cdn.setText(ctx.settings.cdn);
        slotTarget.setText(String.valueOf(ctx.settings.slotTarget));
        budget.setText(String.valueOf(ctx.settings.budget));
        (theme.get().equals("dark") ? dark : light).setSelected(true); // the side menu can switch it too
        keyState.setText(vaultUi.statusText());
        boolean stored = vaultUi.hasStoredKey();
        changePin.setDisable(!stored);
        removePin.setDisable(!stored);
        lock.setDisable(!stored);
    }

    private void save() {
        String c = cdn.getText().strip();
        if (!c.isEmpty() && (!c.startsWith("https://") || !c.contains("%s"))) {
            cdnResult.setText("Adres https:// ile başlamalı ve dosya adı yerine %s içermeli.");
            cdnResult.getStyleClass().setAll("label", "error-text", "small");
            return;
        }
        ctx.settings.cdn = c;
        ctx.settings.slotTarget = parse(slotTarget.getText(), ProjectSettings.DEFAULT_SLOT_TARGET);
        ctx.settings.budget = parse(budget.getText(), ProjectSettings.DEFAULT_BUDGET);
        ctx.settingsChanged();
        cdnResult.setText("Kaydedildi.");
        cdnResult.getStyleClass().setAll("label", "muted", "small");
        ctx.refreshAll();
    }

    private static int parse(String s, int fallback) {
        try {
            int v = Integer.parseInt(s.strip());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void testCdn() {
        String c = cdn.getText().strip();
        if (!c.startsWith("https://") || !c.contains("%s")) {
            cdnResult.setText("Adres https:// ile başlamalı ve %s içermeli.");
            cdnResult.getStyleClass().setAll("label", "error-text", "small");
            return;
        }
        Slot first = ctx.cfg.slots().get(0);
        cdnResult.setText("Deneniyor: " + first.outputName() + " …");
        cdnResult.getStyleClass().setAll("label", "muted", "small");
        ctx.background.submit(() -> {
            Optional<String> r = new Cdn(c).fetch(first.outputName());
            Platform.runLater(() -> {
                if (r.isPresent()) {
                    cdnResult.setText("Çalışıyor: " + first.outputName() + " okundu (" + Ui.number(r.get().length())
                            + " karakter). Kaydet'e basmayı unutma.");
                    cdnResult.getStyleClass().setAll("label", "small");
                } else {
                    cdnResult.setText("Okunamadı (PC-302): adres yanlış olabilir ya da internet bağlantısı yok.");
                    cdnResult.getStyleClass().setAll("label", "error-text", "small");
                }
            });
        });
    }
}
