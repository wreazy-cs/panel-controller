package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Backup;
import io.github.wreazycs.panelcontroller.Builder;
import io.github.wreazycs.panelcontroller.Cdn;
import io.github.wreazycs.panelcontroller.Comparer;
import io.github.wreazycs.panelcontroller.PanelValidator;
import io.github.wreazycs.panelcontroller.ProjectConfig;
import io.github.wreazycs.panelcontroller.ProjectStatus;
import io.github.wreazycs.panelcontroller.ProjectStatus.SlotRow;
import io.github.wreazycs.panelcontroller.ProjectStatus.State;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.prefs.Preferences;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/** Desktop front end: the same operations as the command line, with status cards and a slot table. */
public final class PanelApp extends Application {

    /** A backup older than this triggers a warning before copying to the clipboard. */
    private static final Duration BACKUP_FRESH = Duration.ofHours(1);

    @FunctionalInterface
    private interface Job {
        int run(PrintStream out) throws Exception;
    }

    private final Preferences prefs = Preferences.userNodeForPackage(PanelApp.class);
    private final ExecutorService worker = daemonPool("job");
    private final ExecutorService background = Executors.newCachedThreadPool(r -> daemon(r, "status"));

    private Stage stage;
    private Path root;
    private Path configFile;
    private ProjectConfig cfg;
    private String lastCompare = "henüz bakılmadı";

    private final Label rootLabel = new Label();
    private final Label configLabel = new Label();
    private final Label cardBackup = new Label("-");
    private final Label cardBuild = new Label("-");
    private final Label cardGit = new Label("-");
    private final Label cardLive = new Label("-");
    private final TableView<SlotRow> table = new TableView<>();
    private final TextArea log = new TextArea();
    private final List<Button> actions = new ArrayList<>();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        root = safePath(prefs.get("root", System.getProperty("user.dir")), Path.of("."));
        String saved = prefs.get("config", "");
        configFile = saved.isEmpty() ? null : safePath(saved, null);
        applyParameters(getParameters().getRaw());

        BorderPane main = new BorderPane();
        main.getStyleClass().add("app");
        main.setPadding(new Insets(12));

        VBox top = new VBox(10, projectBar(), cards(), buttons());
        main.setTop(top);

        buildTable();
        log.setEditable(false);
        log.setWrapText(false);
        log.getStyleClass().add("log");
        SplitPane split = new SplitPane(table, log);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.45);
        BorderPane.setMargin(split, new Insets(10, 0, 0, 0));
        main.setCenter(split);

        Scene scene = new Scene(main, 1040, 720);
        scene.getStylesheets().add(getClass().getResource("/panel-app.css").toExternalForm());
        primary.setTitle("Panel Controller");
        primary.setScene(scene);
        primary.show();

        reload();
    }

    @Override
    public void stop() {
        worker.shutdownNow();
        background.shutdownNow();
    }

    // ------------------------------------------------------------------ layout

    private HBox projectBar() {
        rootLabel.getStyleClass().add("path");
        configLabel.getStyleClass().add("path");
        Button chooseRoot = new Button("Klasör seç…");
        chooseRoot.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Proje klasörü");
            if (Files.isDirectory(root)) {
                dc.setInitialDirectory(root.toFile());
            }
            File f = dc.showDialog(stage);
            if (f != null) {
                root = f.toPath();
                prefs.put("root", root.toString());
                reload();
            }
        });
        Button chooseConfig = new Button("Ayar dosyası…");
        chooseConfig.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Ayar dosyası (.properties)");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Properties", "*.properties"));
            File f = fc.showOpenDialog(stage);
            if (f != null) {
                configFile = f.toPath();
                prefs.put("config", configFile.toString());
                reload();
            }
        });
        Button clearConfig = new Button("×");
        clearConfig.setTooltip(new javafx.scene.control.Tooltip("Ayar dosyasını kaldır"));
        clearConfig.setOnAction(e -> {
            configFile = null;
            prefs.remove("config");
            reload();
        });
        VBox paths = new VBox(2, rootLabel, configLabel);
        HBox.setHgrow(paths, Priority.ALWAYS);
        return new HBox(8, paths, chooseRoot, chooseConfig, clearConfig);
    }

    private HBox cards() {
        HBox box = new HBox(10,
                card("Son yedek", cardBackup),
                card("Derleme", cardBuild),
                card("Canlıyla fark", cardLive),
                card("Git", cardGit));
        box.getChildren().forEach(c -> HBox.setHgrow(c, Priority.ALWAYS));
        return box;
    }

    private static VBox card(String title, Label value) {
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        value.getStyleClass().add("card-value");
        value.setWrapText(true);
        VBox v = new VBox(4, t, value);
        v.getStyleClass().add("card");
        v.setMaxWidth(Double.MAX_VALUE);
        return v;
    }

    private FlowPane buttons() {
        FlowPane pane = new FlowPane(8, 8);
        pane.getChildren().addAll(
                action("Derle", () -> job("Derle",
                        out -> new Builder(cfg(), out).build(Builder.Mode.PANEL, List.of()), null)),
                action("Doğrula", () -> job("Doğrula", out -> new PanelValidator(cfg(), out).run(), null)),
                action("Derle + Doğrula", () -> job("Derle + Doğrula", out -> {
                    ProjectConfig c = cfg();
                    int rc = new Builder(c, out).build(Builder.Mode.PANEL, List.of());
                    if (rc != 0) {
                        return rc;
                    }
                    out.println();
                    return new PanelValidator(c, out).run();
                }, null)),
                action("Yedek al", () -> job("Yedek al",
                        out -> new Backup(cfg(), out).run(new Cdn(cfg().cdnTemplate())), null)),
                action("Canlıyla karşılaştır", () -> {
                    ProjectConfig c = cfg();
                    job("Canlıyla karşılaştır",
                            out -> new Comparer(c, out).run(new Cdn(c.cdnTemplate()), c.outputDir()),
                            rc -> lastCompare = rc == 0 ? "canlıyla aynı" : rc == 1 ? "fark var" : "okunamadı");
                }),
                action("Panoya kopyala", this::copySelected),
                action("Yedekten geri yükle…", this::restoreSelected));
        Button refresh = new Button("Yenile");
        refresh.setOnAction(e -> refresh());
        Button clear = new Button("Günlüğü temizle");
        clear.setOnAction(e -> log.clear());
        pane.getChildren().addAll(refresh, clear);
        return pane;
    }

    private Button action(String text, Runnable run) {
        Button b = new Button(text);
        b.getStyleClass().add("primary");
        b.setOnAction(e -> {
            if (cfg == null) {
                info("Önce geçerli bir proje klasörü seç.");
                return;
            }
            run.run();
        });
        actions.add(b);
        return b;
    }

    private void buildTable() {
        table.getColumns().add(column("Dosya", 130, r -> r.slot().outputName()));
        table.getColumns().add(column("Panel slotu", 170, r -> r.slot().panelName()));
        table.getColumns().add(column("Parça", 70, r -> String.valueOf(r.pieces())));
        table.getColumns().add(column("Karakter", 100, r -> r.outputChars() < 0 ? "-" : String.valueOf(r.outputChars())));
        table.getColumns().add(column("Hedef %", 90, r -> r.outputChars() < 0 ? "-" : String.format("%.1f", r.percent())));
        table.getColumns().add(column("Durum", 330, PanelApp::stateText));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Proje klasöründe slot bulunamadı."));
    }

    private static TableColumn<SlotRow, String> column(String title, double width, Function<SlotRow, String> f) {
        TableColumn<SlotRow, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(f.apply(cell.getValue())));
        return c;
    }

    static String stateText(SlotRow r) {
        return switch (r.state()) {
            case EMPTY -> "boş slot";
            case NO_OUTPUT -> "! henüz derlenmedi";
            case STALE -> "! kaynak çıktıdan yeni, yeniden derle";
            case OVER -> "! slot hedefini aşıyor";
            case OK -> "hazır";
        };
    }

    // ------------------------------------------------------------------- state

    private ProjectConfig cfg() {
        return cfg;
    }

    private void reload() {
        rootLabel.setText("Proje: " + root);
        configLabel.setText("Ayar : " + (configFile == null ? "(yok, varsayılanlar)" : configFile));
        try {
            cfg = ProjectConfig.load(root, configFile);
        } catch (IOException | RuntimeException e) {
            cfg = null;
            table.getItems().clear();
            log("Ayarlar okunamadı: " + e.getMessage());
        }
        lastCompare = "henüz bakılmadı";
        refresh();
    }

    private void refresh() {
        if (cfg == null) {
            cardBackup.setText("-");
            cardBuild.setText("-");
            cardGit.setText("-");
            cardLive.setText("-");
            return;
        }
        ProjectConfig c = cfg;
        try {
            List<SlotRow> rows = ProjectStatus.slotRows(c);
            table.getItems().setAll(rows);
            long stale = rows.stream().filter(r -> r.state() == State.STALE || r.state() == State.NO_OUTPUT).count();
            long over = rows.stream().filter(r -> r.state() == State.OVER).count();
            cardBuild.setText(stale > 0 ? stale + " dosya güncel değil, derle"
                    : over > 0 ? over + " dosya slot hedefini aşıyor" : "güncel");
            cardBuild.getStyleClass().removeAll("warn", "good");
            cardBuild.getStyleClass().add(stale > 0 || over > 0 ? "warn" : "good");

            Optional<Duration> age = ProjectStatus.backupAge(c, Clock.systemDefaultZone());
            cardBackup.setText(age.map(ProjectStatus::describeAge).orElse("hiç yedek yok"));
            cardBackup.getStyleClass().removeAll("warn", "good");
            cardBackup.getStyleClass().add(age.isPresent() && age.get().compareTo(BACKUP_FRESH) <= 0 ? "good" : "warn");
        } catch (IOException e) {
            log("Durum okunamadı: " + e.getMessage());
        }
        cardLive.setText(lastCompare);
        cardGit.setText("bakılıyor…");
        background.submit(() -> {
            String text = ProjectStatus.git(c.root()).orElse("git deposu değil");
            Platform.runLater(() -> cardGit.setText(text));
        });
    }

    // -------------------------------------------------------------------- jobs

    private void job(String title, Job job, IntConsumer onDone) {
        setBusy(true);
        log("\n▶ " + title);
        PrintStream out = new PrintStream(new LogStream(), true, StandardCharsets.UTF_8);
        worker.submit(() -> {
            int rc;
            try {
                rc = job.run(out);
            } catch (Exception e) {
                out.println("HATA: " + e.getMessage());
                rc = 2;
            }
            out.flush();
            int code = rc;
            Platform.runLater(() -> {
                if (onDone != null) {
                    onDone.accept(code);
                }
                log(code == 0 ? "✓ bitti" : "✗ bitti, çıkış kodu " + code);
                setBusy(false);
                refresh();
            });
        });
    }

    private void setBusy(boolean busy) {
        actions.forEach(b -> b.setDisable(busy));
    }

    private void log(String line) {
        log.appendText(line + "\n");
    }

    /** Collects what the core classes print and appends it to the log area. */
    private final class LogStream extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            buf.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buf.write(b, off, len);
        }

        @Override
        public synchronized void flush() {
            if (buf.size() == 0) {
                return;
            }
            String text = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            Platform.runLater(() -> log.appendText(text));
        }
    }

    // ----------------------------------------------------------- clipboard flow

    private void copySelected() {
        SlotRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            info("Önce tablodan kopyalanacak dosyayı seç.");
            return;
        }
        Path file = cfg.outputDir().resolve(row.slot().outputName());
        if (!Files.isRegularFile(file)) {
            info(row.slot().outputName() + " henüz yok. Önce 'Derle'.");
            return;
        }
        List<String> warnings = new ArrayList<>();
        if (row.state() == State.STALE) {
            warnings.add("Kaynak dosyalar çıktıdan yeni; 'Derle' çalıştırılmamış.");
        }
        if (row.state() == State.OVER) {
            warnings.add("Dosya slot hedefini aşıyor.");
        }
        try {
            Optional<Duration> age = ProjectStatus.backupAge(cfg, Clock.systemDefaultZone());
            if (age.isEmpty()) {
                warnings.add("Hiç yedek alınmamış. Yüklemeden önce 'Yedek al'.");
            } else if (age.get().compareTo(BACKUP_FRESH) > 0) {
                warnings.add("Son yedek " + ProjectStatus.describeAge(age.get()) + " alınmış.");
            }
        } catch (IOException e) {
            warnings.add("Yedek durumu okunamadı: " + e.getMessage());
        }
        if (!warnings.isEmpty() && !confirm(row.slot().outputName() + " panoya kopyalanacak", warnings)) {
            return;
        }
        toClipboard(file, row.slot().outputName());
    }

    private void restoreSelected() {
        SlotRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            info("Önce tablodan geri yüklenecek dosyayı seç.");
            return;
        }
        try {
            Backup backups = Backup.quiet(cfg);
            List<String> stamps = new ArrayList<>();
            for (String s : backups.list()) {
                if (backups.find(s, row.slot().outputName()).isPresent()) {
                    stamps.add(s);
                }
            }
            if (stamps.isEmpty()) {
                info("Bu dosya için yedek yok: " + row.slot().outputName());
                return;
            }
            ChoiceDialog<String> dlg = new ChoiceDialog<>(stamps.get(0), stamps);
            dlg.setTitle("Yedekten geri yükle");
            dlg.setHeaderText(row.slot().outputName() + " için yedek seç (en yenisi üstte)");
            dlg.setContentText("Yedek:");
            Optional<String> pick = dlg.showAndWait();
            if (pick.isEmpty()) {
                return;
            }
            Path file = backups.find(pick.get(), row.slot().outputName())
                    .orElseThrow(() -> new IOException("yedek bulunamadı"));
            toClipboard(file, pick.get() + " / " + row.slot().outputName());
            log("Panelde ilgili slota yapıştırıp kaydet; sonra 'Canlıyla karşılaştır' ile doğrula.");
        } catch (IOException e) {
            info("Yedekler okunamadı: " + e.getMessage());
        }
    }

    private void toClipboard(Path file, String label) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            log("✓ " + label + " panoya kopyalandı (" + text.length() + " karakter).");
        } catch (IOException e) {
            info("Dosya okunamadı: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- helpers

    private void info(String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait();
    }

    private boolean confirm(String header, List<String> lines) {
        ButtonType go = new ButtonType("Yine de kopyala", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.WARNING, String.join("\n", lines), go, ButtonType.CANCEL);
        a.setHeaderText(header);
        a.initOwner(stage);
        return a.showAndWait().filter(b -> b == go).isPresent();
    }

    /** Command line overrides: {@code --root <dir>} and {@code --config <file>} (not remembered). */
    private void applyParameters(List<String> args) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).equals("--root")) {
                root = safePath(args.get(++i), root);
            } else if (args.get(i).equals("--config")) {
                configFile = safePath(args.get(++i), configFile);
            }
        }
    }

    private static Path safePath(String text, Path fallback) {
        try {
            return Path.of(text);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static ExecutorService daemonPool(String name) {
        return Executors.newSingleThreadExecutor(r -> daemon(r, name));
    }
}
