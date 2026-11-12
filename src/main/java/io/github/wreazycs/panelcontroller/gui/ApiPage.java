package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.ApiProbe;
import io.github.wreazycs.panelcontroller.Finding;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * API: read-only probe (GET) against the store's API with the key from the session. The address,
 * path and how the key is sent come from the panel's API documentation, which is not public.
 */
final class ApiPage implements Page {
    private final AppContext ctx;
    private final VaultUi vaultUi;
    private final ScrollPane view;

    private final Label keyState = Ui.label("", "strong");
    private final Label ip = Ui.label("", "mono");
    private final TextField base = new TextField();
    private final TextField path = new TextField();
    private final ComboBox<ApiProbe.Auth> auth = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField prefix = new TextField();
    private final VBox result = new VBox(8);

    ApiPage(AppContext ctx, VaultUi vaultUi) {
        this.ctx = ctx;
        this.vaultUi = vaultUi;

        HBox banner = new HBox(12, Icons.of("key", 20, "info"), Ui.wrapped(
                "PlatinMarket Reform API (developer.platinmarket.com): ürün, sipariş, kategori, marka, kur gibi verileri verir; "
                        + "tema CSS/JS slotları için uç noktası YOK, bu yüzden panele yükleme elle kalır. Anahtar panelde "
                        + "Araçlar > API Anahtarları'nda IP adresiyle oluşturulur. Bu sayfa yalnızca OKUR (GET), yönlendirmeleri "
                        + "izlemez ve anahtarı çıktının hiçbir yerinde göstermez."));
        banner.getStyleClass().addAll("banner", "info");

        Button keyButton = Ui.button("Anahtarı gir / değiştir…", "key");
        keyButton.setOnAction(e -> {
            vaultUi.keyDialog();
            refresh();
        });
        Button ipButton = Ui.button("Genel IP adresimi göster", "server");
        ipButton.setOnAction(e -> showIp());
        HBox keyRow = new HBox(10, keyState, Ui.spacer(), keyButton);
        keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox ipRow = new HBox(10, ipButton, ip);
        ipRow.setAlignment(Pos.CENTER_LEFT);
        VBox keyCard = Ui.card(Ui.cardHeader("Anahtar ve IP",
                        "Anahtar IP'ye bağlı: panelde anahtar oluştururken bu bilgisayarın genel IP adresini yaz. "
                                + "IP düğmesi adresi öğrenmek için api.ipify.org'a bir istek atar; yalnızca basınca."),
                keyRow, ipRow);

        base.setPromptText("https://… (panelin API belgesindeki adres)");
        path.setPromptText("/");
        auth.getItems().setAll(ApiProbe.Auth.values());
        auth.setCellFactory(l -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(ApiProbe.Auth a, boolean empty) {
                super.updateItem(a, empty);
                setText(empty || a == null ? null : a.label);
            }
        });
        auth.setButtonCell(auth.getCellFactory().call(null));
        auth.valueProperty().addListener((o, a, b) -> {
            boolean none = b == ApiProbe.Auth.NONE;
            name.setDisable(none);
            prefix.setDisable(none || b == ApiProbe.Auth.QUERY);
        });
        name.setPromptText("Authorization / X-Api-Key / api_key");
        prefix.setPromptText("Bearer  (boş olabilir)");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(c0, c1);
        ComboBox<ApiProbe.Preset> preset = new ComboBox<>();
        preset.getItems().setAll(ApiProbe.PRESETS);
        preset.setPromptText("Hazır okuma isteği seç (Reform API)");
        preset.setCellFactory(l -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(ApiProbe.Preset p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.label() + "   " + p.path());
            }
        });
        preset.setButtonCell(preset.getCellFactory().call(null));
        preset.setOnAction(e -> {
            ApiProbe.Preset p = preset.getValue();
            if (p != null) {
                base.setText(ApiProbe.REFORM_BASE);
                path.setText(p.path());
                auth.setValue(ApiProbe.Auth.QUERY);
                name.setText("access_token");
                prefix.setText("");
            }
        });
        preset.setMaxWidth(Double.MAX_VALUE);
        grid.addRow(0, Ui.label("Hazır istek", "strong"), preset);
        grid.addRow(1, Ui.label("Adres", "strong"), base);
        grid.addRow(2, Ui.label("Yol", "strong"), path);
        grid.addRow(3, Ui.label("Anahtar nasıl gider", "strong"), auth);
        grid.addRow(4, Ui.label("Başlık / parametre adı", "strong"), name);
        grid.addRow(5, Ui.label("Önek", "strong"), prefix);
        Button send = ctx.guard(Ui.button("Okuma denemesi (GET)", "play", "primary"));
        send.setOnAction(e -> probe());
        VBox form = Ui.card(Ui.cardHeader("Okuma denemesi",
                        "Hazır bir istek seç ya da developer.platinmarket.com belgesindeki yolu yaz. Kişisel veri döndüren uçlar "
                                + "(üyeler, siparişler) hazır listede bilerek yok. Hata kodları için kılavuzda PC-710."),
                grid, new HBox(send));

        VBox resultCard = Ui.card(Ui.cardHeader("Sonuç", null), result);
        result.getChildren().add(Ui.label("Henüz istek gönderilmedi.", "muted"));

        VBox content = new VBox(16, banner, keyCard, form, resultCard);
        content.setPadding(new Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
    }

    @Override public String id() { return "api"; }
    @Override public String title() { return "API"; }
    @Override public String subtitle() { return "Mağazanın API'sine güvenli okuma denemesi."; }
    @Override public String icon() { return "key"; }
    @Override public Node view() { return view; }

    @Override
    public void refresh() {
        keyState.setText(vaultUi.statusText());
        ProjectSettings s = ctx.settings;
        if (base.getText().isEmpty()) base.setText(s.apiBase);
        if (path.getText().isEmpty()) path.setText(s.apiPath);
        if (name.getText().isEmpty()) name.setText(s.apiName);
        if (prefix.getText().isEmpty()) prefix.setText(s.apiPrefix);
        if (auth.getValue() == null) {
            try {
                auth.setValue(ApiProbe.Auth.valueOf(s.apiAuth));
            } catch (IllegalArgumentException e) {
                auth.setValue(ApiProbe.Auth.HEADER);
            }
        }
    }

    private void showIp() {
        ip.setText("soruluyor…");
        ctx.background.submit(() -> {
            String text;
            try {
                text = ApiProbe.publicIp();
            } catch (Exception e) {
                text = "alınamadı: " + e.getMessage();
            }
            String t = text;
            Platform.runLater(() -> ip.setText(t));
        });
    }

    private void probe() {
        ApiProbe.Auth a = auth.getValue();
        ApiProbe.Request req = new ApiProbe.Request(base.getText().strip(), path.getText().strip(), a,
                name.getText().strip(), prefix.getText());
        String host;
        try {
            host = URI.create(req.base()).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (a != ApiProbe.Auth.NONE && host != null && !host.equalsIgnoreCase(ctx.settings.apiTrustedHost)) {
            if (!Ui.confirm(ctx.stage, "Anahtar şu sunucuya gönderilecek: " + host,
                    "Bu adres panelin API belgesinde yazan adres mi?\nOnaylarsan bu proje için hatırlanır; başka bir sunucu "
                            + "için yeniden sorulur.", "Evet, gönder")) {
                return;
            }
            ctx.settings.apiTrustedHost = host.toLowerCase(Locale.ROOT);
        }
        ctx.settings.apiBase = req.base();
        ctx.settings.apiPath = req.path();
        ctx.settings.apiAuth = a.name();
        ctx.settings.apiName = req.name();
        ctx.settings.apiPrefix = req.prefix();
        ctx.settings.saveApi();

        char[] key = a == ApiProbe.Auth.NONE ? new char[0] : ctx.session.copy().orElse(new char[0]);
        ctx.run("API okuma denemesi", out -> {
            try {
                ApiProbe.Result r = ApiProbe.get(req, key);
                out.println("GET " + r.url() + " -> " + r.status());
                return r;
            } finally {
                Arrays.fill(key, '\0');
            }
        }, (e, r) -> {
            show(r);
            e.done(r.ok() ? "ok" : r.problem().problem().severity() == io.github.wreazycs.panelcontroller.Problem.Severity.WARNING
                    ? "warn" : "err", (r.status() == 0 ? "" : "HTTP " + r.status() + ": ") + r.meaning(),
                    r.problem() == null ? List.<Finding>of() : List.of(r.problem()));
        });
    }

    private void show(ApiProbe.Result r) {
        result.getChildren().clear();
        String kind = r.ok() ? "ok" : r.status() >= 300 && r.status() < 500 && r.status() != 401 && r.status() != 403 ? "warn" : "err";
        HBox head = new HBox(10, Ui.chip(r.status() == 0 ? "yanıt yok" : "HTTP " + r.status(), kind), Ui.wrapped(r.meaning()));
        head.setAlignment(Pos.CENTER_LEFT);
        result.getChildren().add(head);
        if (!r.url().isEmpty()) {
            result.getChildren().add(Ui.wrapped("GET " + r.url(), "mono", "small"));
        }
        r.headers().forEach((k, v) -> result.getChildren().add(Ui.wrapped(k + ": " + v, "mono", "small", "muted")));
        if (!r.body().isEmpty()) {
            TextArea body = new TextArea(r.body());
            body.setEditable(false);
            body.setWrapText(true);
            body.setPrefRowCount(16);
            body.getStyleClass().add("raw-output");
            result.getChildren().add(body);
            result.getChildren().add(Ui.label(Ui.number(r.size()) + " bayt" + (r.truncated() ? " (ilk 200 KB gösterildi)" : "")
                    + " · anahtar her yerde *** ile maskelendi", "small", "muted"));
        }
        if (r.problem() != null) {
            Label code = Ui.chip(r.problem().problem().code(), Ui.kind(r.problem().problem().severity()));
            code.getStyleClass().add("link");
            code.setOnMouseClicked(e -> ctx.openGuide(r.problem().problem().code()));
            HBox fix = new HBox(8, code, Ui.wrapped("Ne yapmalı: " + r.problem().problem().fix(), "small"));
            fix.setAlignment(Pos.CENTER_LEFT);
            result.getChildren().add(fix);
            if (!r.problem().detail().isEmpty()) {
                result.getChildren().add(Ui.wrapped(r.problem().detail(), "small", "muted"));
            }
        }
    }
}
