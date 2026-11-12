package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Backup;
import io.github.wreazycs.panelcontroller.Builder;
import io.github.wreazycs.panelcontroller.Comparer;
import io.github.wreazycs.panelcontroller.Comparer.LiveState;
import io.github.wreazycs.panelcontroller.Finding;
import io.github.wreazycs.panelcontroller.GitRepo;
import io.github.wreazycs.panelcontroller.PanelValidator;
import io.github.wreazycs.panelcontroller.Problem;
import io.github.wreazycs.panelcontroller.ProjectStatus;
import io.github.wreazycs.panelcontroller.ProjectStatus.SlotRow;
import io.github.wreazycs.panelcontroller.ProjectStatus.State;
import io.github.wreazycs.panelcontroller.Slot;
import io.github.wreazycs.panelcontroller.Workflow;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Yayın: everything needed to put a change live. Prepare (backup, build, validate), see which files
 * differ from the live site, paste them in order with the upload wizard, check again.
 */
final class PublishPage implements Page {
    /** A backup older than this is refreshed (or explicitly skipped) before pasting. */
    private static final Duration BACKUP_FRESH = Duration.ofMinutes(120);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** One row of the file table. */
    record Row(Slot slot, int order, SlotRow status, Comparer.FileResult live) {}

    private final AppContext ctx;
    private final ScrollPane view;
    private final VBox content = new VBox(16);

    private final StatCard statBackup = new StatCard("Son yedek");
    private final StatCard statLive = new StatCard("Canlıyla fark");
    private final StatCard statBuild = new StatCard("Derleme");
    private final StatCard statGit = new StatCard("Git");

    private final StepView stepBackup = new StepView(1, "Yedek al", "Canlı dosyaların kopyası");
    private final StepView stepBuild = new StepView(2, "Derle", "kaynak/ → cikti/");
    private final StepView stepValidate = new StepView(3, "Doğrula", "Panel simülasyonu");

    private final VBox notice = new VBox();
    private final TableView<Row> table = new TableView<>();
    private final VBox filesCard;
    private final VBox emptyCard;

    // upload wizard
    private List<Slot> wizardQueue;
    private int wizardIndex;

    PublishPage(AppContext ctx) {
        this.ctx = ctx;

        HBox stats = new HBox(12, statBackup, statLive, statBuild, statGit);

        Button prepare = ctx.guard(Ui.button("Hazırla", "play", "primary"));
        prepare.setOnAction(e -> prepare());
        Button compare = ctx.guard(Ui.button("Canlıyla karşılaştır", "compare"));
        compare.setOnAction(e -> compare(null));
        MenuButton single = new MenuButton("Tek adım");
        single.getStyleClass().add("btn");
        MenuItem onlyBackup = new MenuItem("Yalnız yedek al");
        onlyBackup.setOnAction(e -> backup(null));
        MenuItem onlyBuild = new MenuItem("Yalnız derle");
        onlyBuild.setOnAction(e -> build(Builder.Mode.PANEL));
        MenuItem onlyValidate = new MenuItem("Yalnız doğrula");
        onlyValidate.setOnAction(e -> validate());
        MenuItem readable = new MenuItem("İnceleme kopyası üret (okunur, panele gitmez)");
        readable.setOnAction(e -> build(Builder.Mode.READABLE));
        single.getItems().addAll(onlyBackup, onlyBuild, onlyValidate, readable);
        ctx.guard(single);

        HBox steps = new HBox(14, stepBackup, line(), stepBuild, line(), stepValidate);
        steps.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, prepare, compare, single);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox prepareCard = Ui.card(
                Ui.cardHeader("Yayına hazırla",
                        "Panele yüklemeden önce: canlının yedeğini al, derle, panel simülasyonundan geçir. Bir adım başarısız olursa "
                                + "sonrakiler çalışmaz ve yükleme yapılmamalı."),
                steps, actions);

        buildTable();
        filesCard = Ui.card(
                Ui.cardHeader("Dosyalar", "Yapıştırma sırasıyla: önce sayfa slotları, en son genel. Satır sonundaki düğme dosyayı panoya alır."),
                table);
        emptyCard = Ui.emptyState("folder", "Bu klasörde kaynak yok",
                "Proje klasörü kaynak/ alt klasörünü içermeli. Ayarlar'dan doğru klasörü seç.",
                goSettings());

        content.getChildren().addAll(stats, prepareCard, notice, filesCard);
        content.setPadding(new javafx.geometry.Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
    }

    private Button goSettings() {
        Button b = Ui.button("Ayarlara git", "settings", "primary");
        b.setOnAction(e -> ctx.navigator.accept("settings"));
        return b;
    }

    private static Region line() {
        Region r = new Region();
        r.getStyleClass().add("step-line");
        return r;
    }

    @Override public String id() { return "publish"; }
    @Override public String title() { return "Yayın"; }
    @Override public String subtitle() { return "Hazırla, canlıyla karşılaştır, farklı dosyaları sırayla panele yapıştır."; }
    @Override public String icon() { return "publish"; }
    @Override public Node view() { return view; }

    // ------------------------------------------------------------------ table

    private void buildTable() {
        table.getColumns().add(textCol("#", 36, r -> r.order() > 0 ? String.valueOf(r.order()) : ""));
        TableColumn<Row, String> file = textCol("Dosya", 150, r -> r.slot().outputName());
        file.setMinWidth(130);
        file.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty ? null : s);
                getStyleClass().remove("mono");
                if (!empty) getStyleClass().add("mono");
            }
        });
        table.getColumns().add(file);
        table.getColumns().add(textCol("Panel slotu", 130, r -> r.slot().panelName()));
        table.getColumns().add(textCol("Boyut", 80, r -> r.status().outputChars() < 0 ? "-" : Ui.number(r.status().outputChars())));
        table.getColumns().add(nodeCol("Hedef", 120, r -> {
            if (r.status().outputChars() < 0) {
                return Ui.label("-", "muted");
            }
            ProgressBar bar = new ProgressBar(Math.min(1.0, r.status().percent() / 100.0));
            bar.setPrefWidth(52);
            bar.setMinWidth(52);
            if (r.status().percent() > 100) bar.getStyleClass().add("over");
            HBox h = new HBox(8, bar, Ui.label(String.format("%%%.0f", r.status().percent()), "small", "muted"));
            h.setAlignment(Pos.CENTER_LEFT);
            return h;
        }));
        table.getColumns().add(nodeCol("Durum", 110, r -> switch (r.status().state()) {
            case NO_OUTPUT -> Ui.chip("derlenmedi", "warn");
            case STALE -> Ui.chip("derlenmeli", "warn");
            case OVER -> Ui.chip("hedef aşıldı", "warn");
            case OK -> Ui.chip("hazır", "ok");
            default -> Ui.chip("boş", "");
        }));
        table.getColumns().add(nodeCol("Canlı", 100, r -> {
            if (ctx.compareRunning) return Ui.chip("bakılıyor…", "");
            if (r.live() == null) return Ui.chip(ctx.lastCompare == null ? "bakılmadı" : "-", "");
            return switch (r.live().state()) {
                case SAME -> Ui.chip("aynı", "ok");
                case DIFFERENT -> Ui.chip("farklı", "warn");
                case UNREADABLE -> Ui.chip("okunamadı", "err");
                case LOCAL_MISSING -> Ui.chip("yerelde yok", "");
            };
        }));
        TableColumn<Row, Row> copy = nodeCol("", 52, r -> {
            if (r.status().outputChars() < 0) return new Region();
            Button b = Ui.iconButton("copy", "Panoya kopyala: " + r.slot().outputName());
            b.disableProperty().bind(ctx.busy);
            b.setOnAction(e -> copyWithChecks(r));
            return b;
        });
        copy.setMinWidth(52);
        copy.setMaxWidth(52);
        table.getColumns().add(copy);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(Ui.label("Derlenmiş dosya yok. Hazırla'ya bas.", "muted"));
        table.setFixedCellSize(40);
        table.prefHeightProperty().bind(table.fixedCellSizeProperty().multiply(
                javafx.beans.binding.Bindings.size(table.getItems()).add(1.4)));
        table.setMinHeight(140);
    }

    private static TableColumn<Row, String> textCol(String title, double width, Function<Row, String> f) {
        TableColumn<Row, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setReorderable(false);
        c.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(f.apply(cell.getValue())));
        return c;
    }

    private static TableColumn<Row, Row> nodeCol(String title, double width, Function<Row, Node> f) {
        TableColumn<Row, Row> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setReorderable(false);
        c.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Row r, boolean empty) {
                super.updateItem(r, empty);
                setText(null);
                setGraphic(empty || r == null ? null : f.apply(r));
            }
        });
        return c;
    }

    // ---------------------------------------------------------------- refresh

    @Override
    public void refresh() {
        List<SlotRow> status;
        try {
            status = ProjectStatus.slotRows(ctx.cfg);
        } catch (IOException e) {
            ErrorLog.write("durum", e);
            status = List.of();
        }
        List<Slot> order = Workflow.pasteOrder(ctx.cfg);
        List<Row> rows = new ArrayList<>();
        for (SlotRow s : status) {
            if (s.state() == State.EMPTY) continue;
            int idx = order.indexOf(s.slot());
            Comparer.FileResult live = ctx.lastCompare == null ? null : ctx.lastCompare.get(s.slot().outputName()).orElse(null);
            rows.add(new Row(s.slot(), idx < 0 ? 0 : idx + 1, s, live));
        }
        rows.sort(Comparator.comparingInt((Row r) -> r.order() == 0 ? Integer.MAX_VALUE : r.order()));
        table.getItems().setAll(rows);

        boolean noSources = rows.isEmpty();
        content.getChildren().remove(emptyCard);
        filesCard.setVisible(!noSources);
        filesCard.setManaged(!noSources);
        if (noSources) {
            content.getChildren().add(emptyCard);
        }

        updateBackupStat();
        updateLiveStat();
        updateBuildStat(rows);
        updateGitStat();
        updateNotice();
    }

    private void updateBackupStat() {
        try {
            Optional<Duration> age = ProjectStatus.backupAge(ctx.cfg, Clock.systemDefaultZone());
            if (age.isEmpty()) {
                statBackup.set("err", "Yok", "Yüklemeden önce yedek al");
            } else if (age.get().toHours() < 24) {
                statBackup.set("ok", ProjectStatus.describeAge(age.get()), latestStamp());
            } else if (age.get().toDays() < 7) {
                statBackup.set("warn", ProjectStatus.describeAge(age.get()), "Yüklemeden önce tazele");
            } else {
                statBackup.set("err", ProjectStatus.describeAge(age.get()), "Çok eski");
            }
        } catch (IOException e) {
            statBackup.set("err", "Okunamadı", e.getMessage());
        }
    }

    private String latestStamp() {
        try {
            List<String> all = Backup.quiet(ctx.cfg).list();
            return all.isEmpty() ? "" : all.get(0);
        } catch (IOException e) {
            return "";
        }
    }

    private void updateLiveStat() {
        if (ctx.cfg.cdnTemplate() == null) {
            statLive.set("", "Ayarlanmadı", "Ayarlar'da CDN adresi gir");
        } else if (ctx.compareRunning) {
            statLive.set("", "Bakılıyor…", "Canlı dosyalar okunuyor");
        } else if (ctx.lastCompare == null) {
            statLive.set("", "Bakılmadı", "Canlıyla karşılaştır'a bas");
        } else {
            Comparer.Report r = ctx.lastCompare;
            String when = ctx.lastCompareTime == null ? "" : ctx.lastCompareTime.format(HM);
            long diff = r.count(LiveState.DIFFERENT);
            long bad = r.count(LiveState.UNREADABLE);
            if (bad > 0 && diff == 0) {
                statLive.set("err", "Okunamadı", bad + " dosya CDN'den okunamadı (" + when + ")");
            } else if (diff > 0) {
                statLive.set("warn", diff + " farklı", "Yüklenmeyi bekliyor ya da önbellek (" + when + ")");
            } else {
                statLive.set("ok", r.count(LiveState.SAME) + "/" + r.files().size() + " aynı", "Canlı = yerel (" + when + ")");
            }
        }
    }

    private void updateBuildStat(List<Row> rows) {
        long stale = rows.stream().filter(r -> r.status().state() == State.STALE || r.status().state() == State.NO_OUTPUT).count();
        long over = rows.stream().filter(r -> r.status().state() == State.OVER).count();
        if (ctx.lastBuildFailure != null) {
            statBuild.set("err", "Hata", ctx.lastBuildFailure.problem().code() + " " + ctx.lastBuildFailure.problem().title());
        } else if (rows.isEmpty()) {
            statBuild.set("", "Kaynak yok", "Proje klasörünü kontrol et");
        } else if (stale > 0) {
            statBuild.set("warn", "Derlenmeli", stale + " dosyanın kaynağı çıktıdan yeni");
        } else if (over > 0) {
            statBuild.set("warn", "Güncel", over + " dosya slot hedefini aşıyor");
        } else {
            statBuild.set("ok", "Güncel", "cikti/ kaynak/ ile güncel");
        }
    }

    private void updateGitStat() {
        Path root = ctx.cfg.root();
        ctx.background.submit(() -> {
            GitRepo git = new GitRepo(root);
            String kind;
            String text;
            String sub;
            if (!git.hasGitDir()) {
                kind = "";
                text = "Depo değil";
                sub = "Bu klasör git ile izlenmiyor";
            } else {
                try {
                    GitRepo.Status s = git.status();
                    if (!s.changes().isEmpty()) {
                        kind = "info";
                        text = s.changes().size() + " değişiklik";
                        sub = "Commit bekliyor · " + s.branch();
                    } else if (s.ahead() > 0) {
                        kind = "warn";
                        text = s.ahead() + " push edilmemiş";
                        sub = "Git sayfasından push et";
                    } else {
                        kind = "ok";
                        text = "Temiz";
                        sub = s.branch();
                    }
                } catch (IOException e) {
                    kind = "err";
                    text = "Okunamadı";
                    sub = e.getMessage();
                }
            }
            String k = kind, t = text, d = sub;
            Platform.runLater(() -> statGit.set(k, t, d));
        });
    }

    private void updateNotice() {
        if (wizardQueue != null) {
            notice.getChildren().setAll(wizardView());
            return;
        }
        if (ctx.lastCompare != null && ctx.lastCompare.count(LiveState.DIFFERENT) > 0) {
            List<String> names = Workflow.pasteOrder(ctx.cfg).stream().map(Slot::outputName)
                    .filter(n -> ctx.lastCompare.get(n).map(f -> f.state() == LiveState.DIFFERENT).orElse(false)).toList();
            Button start = ctx.guard(Ui.button("Yükleme sihirbazını başlat", "upload", "primary"));
            start.setMinWidth(Region.USE_PREF_SIZE);
            start.setOnAction(e -> startWizard());
            Label text = Ui.wrapped(names.size() + " dosya canlıdan farklı: " + String.join(", ", names)
                    + ". Sihirbaz bunları doğru sırayla panoya alır.");
            javafx.scene.layout.HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);
            HBox banner = new HBox(12, Icons.of("alert", 20, "warn"), text, start);
            banner.getStyleClass().addAll("banner", "warn");
            notice.getChildren().setAll(banner);
        } else if (ctx.lastCompare != null && ctx.lastCompare.allSame() && !ctx.lastCompare.files().isEmpty()) {
            HBox banner = new HBox(12, Icons.of("check", 20, "ok"),
                    Ui.wrapped("Canlı site yerel çıktıyla aynı. Yüklenecek dosya yok."));
            banner.getStyleClass().addAll("banner", "ok");
            notice.getChildren().setAll(banner);
        } else {
            notice.getChildren().clear();
        }
    }

    // ---------------------------------------------------------------- actions

    private void prepare() {
        if (!ctx.requireCdn()) {
            return;
        }
        stepBackup.set(StepView.RUNNING, "Canlı dosyalar indiriliyor");
        stepBuild.set(StepView.PENDING, "kaynak/ → cikti/");
        stepValidate.set(StepView.PENDING, "Panel simülasyonu");
        ctx.run("Hazırla (yedek + derle + doğrula)", out -> Workflow.prepare(ctx.cfg, out), (e, r) -> {
            showBackupStep(r.backup());
            showBuildStep(r.build());
            showValidateStep(r.validation());
            ctx.lastBuildFailure = r.failedAt() == Workflow.Step.BUILD || r.failedAt() == Workflow.Step.VALIDATE ? r.failure() : null;
            if (r.ok()) {
                List<String> order = Workflow.pasteOrder(ctx.cfg).stream().map(Slot::outputName).toList();
                List<Finding> warnings = new ArrayList<>(r.build().warnings());
                warnings.addAll(r.validation().allWarnings().stream()
                        .filter(f -> f.problem() != Problem.SLOT_OVER_TARGET).toList());
                e.done(warnings.isEmpty() ? "ok" : "warn",
                        "Hazır. Yapıştırma sırası: " + String.join(" → ", order) + ". Şimdi canlıyla karşılaştırılıyor.", warnings);
                Platform.runLater(() -> compare(null));
            } else {
                List<Finding> problems = new ArrayList<>();
                if (r.validation() != null) {
                    problems.addAll(r.validation().allErrors());
                } else if (r.failure() != null) {
                    problems.add(r.failure());
                }
                String step = switch (r.failedAt()) {
                    case BACKUP -> "Yedek alınamadı";
                    case BUILD -> "Derleme durdu";
                    case VALIDATE -> "Doğrulama geçmedi";
                };
                e.done("err", step + ". Panele yükleme yapma.", problems);
            }
        });
    }

    private void showBackupStep(Backup.Report b) {
        if (b == null) {
            stepBackup.set(StepView.FAIL, "Mağaza CDN adresi yok");
        } else if (b.ok()) {
            stepBackup.set(StepView.OK, b.saved() + " dosya yedeklendi");
        } else {
            stepBackup.set(StepView.FAIL, b.failed() + " dosya indirilemedi");
        }
    }

    private void showBuildStep(Builder.Report b) {
        if (b == null) {
            stepBuild.set(StepView.PENDING, "Çalışmadı");
        } else if (b.ok()) {
            stepBuild.set(StepView.OK, b.outputs().size() + " dosya · " + Ui.number(b.total()) + " karakter");
        } else {
            stepBuild.set(StepView.FAIL, b.failure().problem().code() + " " + b.failure().problem().title());
        }
    }

    private void showValidateStep(PanelValidator.Report v) {
        if (v == null) {
            stepValidate.set(StepView.PENDING, "Çalışmadı");
        } else if (v.ok()) {
            stepValidate.set(StepView.OK, "Hepsi temiz");
        } else {
            stepValidate.set(StepView.FAIL, v.allErrors().size() + " sorun");
        }
    }

    private void backup(Runnable then) {
        if (!ctx.requireCdn()) {
            return;
        }
        stepBackup.set(StepView.RUNNING, "Canlı dosyalar indiriliyor");
        ctx.run("Yedek al", out -> new Backup(ctx.cfg, out).run(ctx.cdn().orElseThrow()), (e, r) -> {
            showBackupStep(r);
            if (r.ok()) {
                e.done("ok", r.saved() + " dosya yedeklendi: " + r.stamp(), List.of());
                if (then != null) {
                    Platform.runLater(then);
                }
            } else {
                e.done("err", "Yedek eksik. Yedek tamamlanmadan panele yükleme.", List.of(r.failure()));
            }
        });
    }

    private void build(Builder.Mode mode) {
        String title = mode == Builder.Mode.PANEL ? "Derle" : "İnceleme kopyası";
        if (mode == Builder.Mode.PANEL) {
            stepBuild.set(StepView.RUNNING, "kaynak/ → cikti/");
        }
        ctx.run(title, out -> new Builder(ctx.cfg, out).build(mode, List.of()), (e, r) -> {
            if (mode == Builder.Mode.PANEL) {
                showBuildStep(r);
                ctx.lastBuildFailure = r.ok() ? null : r.failure();
            }
            if (r.ok()) {
                e.done(r.warnings().isEmpty() ? "ok" : "warn", r.outputs().size() + " dosya, toplam " + Ui.number(r.total())
                        + " / " + Ui.number(r.budget()) + " karakter → " + ctx.cfg.rel(r.target()) + "/", r.warnings());
            } else {
                e.done("err", "Derleme durdu.", List.of(r.failure()));
            }
        });
    }

    private void validate() {
        stepValidate.set(StepView.RUNNING, "Panel simülasyonu");
        ctx.run("Doğrula", out -> new PanelValidator(ctx.cfg, out).run(), (e, r) -> {
            showValidateStep(r);
            List<Finding> all = new ArrayList<>(r.allErrors());
            all.addAll(r.allWarnings());
            e.done(r.ok() ? (r.allWarnings().isEmpty() ? "ok" : "warn") : "err",
                    r.ok() ? "Hepsi temiz. Panele yüklenebilir." : r.allErrors().size() + " sorun var. Panele yükleme.", all);
        });
    }

    /** Live comparison as a job; {@code then} runs afterwards (on the UI thread). */
    private void compare(Runnable then) {
        if (!ctx.requireCdn()) {
            return;
        }
        ctx.run("Canlıyla karşılaştır", out -> new Comparer(ctx.cfg, out).run(ctx.cdn().orElseThrow(), ctx.cfg.outputDir()),
                (e, r) -> {
                    ctx.lastCompare = r;
                    ctx.lastCompareTime = LocalDateTime.now();
                    List<Finding> f = liveFindings(r);
                    long diff = r.count(LiveState.DIFFERENT);
                    if (r.allSame()) {
                        e.done("ok", "Tüm dosyalar canlıyla aynı (" + r.files().size() + ").", f);
                    } else if (r.count(LiveState.UNREADABLE) > 0) {
                        e.done("err", r.count(LiveState.UNREADABLE) + " dosya okunamadı, " + diff + " dosya farklı.", f);
                    } else {
                        e.done("warn", diff + " dosya farklı. Yüklemediysen yükleme sihirbazını başlat; yüklediysen önce "
                                + "önbelleği şüphelen (Ctrl+Shift+R, gizli sekme).", f);
                    }
                    if (then != null) {
                        Platform.runLater(then);
                    }
                });
    }

    static List<Finding> liveFindings(Comparer.Report r) {
        List<Finding> out = new ArrayList<>();
        for (Comparer.FileResult f : r.files()) {
            switch (f.state()) {
                case DIFFERENT -> out.add(Finding.of(Problem.LIVE_DIFFERENT, f.name(),
                        "canlı " + Ui.number(f.liveChars()) + " / yerel " + Ui.number(f.localChars()) + " karakter, +"
                                + f.added() + " −" + f.removed() + " satır"));
                case UNREADABLE -> out.add(Finding.of(Problem.CDN_UNREADABLE, f.name(), ""));
                case LOCAL_MISSING -> out.add(Finding.of(Problem.LOCAL_MISSING, f.name(),
                        "canlıda " + Ui.number(f.liveChars()) + " karakter"));
                default -> { }
            }
        }
        return out;
    }

    /** Quiet comparison at start-up (no card unless something is wrong), like the Python window did. */
    void compareQuietly() {
        if (ctx.cfg.cdnTemplate() == null || ctx.compareRunning || ctx.busy.get()) {
            return;
        }
        ctx.compareRunning = true;
        refresh();
        var cfg = ctx.cfg;
        ctx.background.submit(() -> {
            Comparer.Report r = null;
            try {
                r = new Comparer(cfg, new PrintStream(OutputStream.nullOutputStream())).run(ctx.cdn().orElseThrow(), cfg.outputDir());
            } catch (IOException | RuntimeException e) {
                ErrorLog.write("açılış karşılaştırması", e);
            }
            Comparer.Report result = r;
            Platform.runLater(() -> {
                ctx.compareRunning = false;
                if (result != null && cfg == ctx.cfg) {
                    ctx.lastCompare = result;
                    ctx.lastCompareTime = LocalDateTime.now();
                }
                refresh();
            });
        });
    }

    // ---------------------------------------------------------------- clipboard

    private void copyWithChecks(Row r) {
        List<String> warnings = new ArrayList<>();
        if (r.status().state() == State.STALE) {
            warnings.add("Kaynak dosyalar çıktıdan yeni: önce Hazırla ya da Derle.");
        }
        if (r.status().state() == State.OVER) {
            warnings.add("Dosya slot hedefini aşıyor.");
        }
        if (!warnings.isEmpty() && !Ui.confirm(ctx.stage, r.slot().outputName() + " panoya kopyalanacak",
                String.join("\n", warnings), "Yine de devam et")) {
            return;
        }
        ensureFreshBackup(() -> {
            if (toClipboard(r.slot())) {
                ctx.activity.note("ok", r.slot().outputName() + " panoya kopyalandı",
                        "Panelde '" + r.slot().panelName() + "' slotunu aç, içeriği sil, Ctrl+V ile yapıştır, kaydet. "
                                + "Sonra Canlıyla karşılaştır.");
            }
        });
    }

    /** Python rule: a backup older than two hours is refreshed (or knowingly skipped) before pasting. */
    private void ensureFreshBackup(Runnable proceed) {
        Optional<Duration> age;
        try {
            age = ProjectStatus.backupAge(ctx.cfg, Clock.systemDefaultZone());
        } catch (IOException e) {
            age = Optional.empty();
        }
        if (age.isPresent() && age.get().compareTo(BACKUP_FRESH) <= 0) {
            proceed.run();
            return;
        }
        String when = age.map(a -> "Son yedek " + ProjectStatus.describeAge(a) + ".").orElse("Hiç yedek yok.");
        int c = Ui.choose(ctx.stage, "Yedek eski", when + "\nPanele yüklemeden önce canlının yedeği alınmalı; bir sorun "
                        + "çıkarsa geri dönmek için gerekir.",
                List.of("Şimdi yedek al ve devam et", "Yedeksiz devam et", "Vazgeç"));
        if (c == 0) {
            backup(proceed);
        } else if (c == 1) {
            proceed.run();
        }
    }

    private boolean toClipboard(Slot slot) {
        Path file = Workflow.output(ctx.cfg, slot);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            return true;
        } catch (IOException e) {
            ctx.activity.start("Panoya kopyalanamadı").done("err", file + ": " + e.getMessage(), List.of());
            return false;
        }
    }

    // ---------------------------------------------------------------- wizard

    private void startWizard() {
        if (ctx.lastCompare == null) {
            Ui.info(ctx.stage, "Önce canlıyla karşılaştır", "Hangi dosyaların yükleneceğini bilmek için önce canlıyla karşılaştırılmalı.");
            return;
        }
        List<Slot> queue = Workflow.pasteOrder(ctx.cfg).stream()
                .filter(s -> ctx.lastCompare.get(s.outputName()).map(f -> f.state() == LiveState.DIFFERENT).orElse(false))
                .toList();
        if (queue.isEmpty()) {
            Ui.info(ctx.stage, "Yüklenecek dosya yok", "Canlıyla farklı dosya yok.");
            return;
        }
        boolean stale = table.getItems().stream().anyMatch(r -> r.status().state() == State.STALE);
        if (stale && !Ui.confirm(ctx.stage, "Çıktı kaynaktan eski",
                "Bazı dosyaların kaynağı son derlemeden sonra değişmiş. Önce Hazırla önerilir.", "Yine de başlat")) {
            return;
        }
        ensureFreshBackup(() -> {
            wizardQueue = queue;
            wizardIndex = 0;
            toClipboard(queue.get(0));
            updateNotice();
        });
    }

    private Node wizardView() {
        Slot s = wizardQueue.get(wizardIndex);
        boolean last = wizardIndex == wizardQueue.size() - 1;
        Button cancel = Ui.button("Vazgeç", "x", "ghost", "small");
        cancel.setOnAction(e -> {
            ctx.activity.note("warn", "Yükleme sihirbazı yarıda bırakıldı",
                    (wizardIndex) + " / " + wizardQueue.size() + " dosya yapıştırıldı. Durumu görmek için canlıyla karşılaştır.");
            wizardQueue = null;
            updateNotice();
        });
        HBox top = new HBox(10, Ui.chip("Adım " + (wizardIndex + 1) + " / " + wizardQueue.size(), "info"),
                Ui.label("Yükleme sihirbazı", "section-title"), Ui.spacer(), cancel);
        top.setAlignment(Pos.CENTER_LEFT);

        Label file = Ui.label(s.outputName(), "wizard-file");
        Label slot = Ui.label("Panel slotu: " + s.panelName(), "strong");
        VBox steps = new VBox(4,
                Ui.label("1.  Panelde \"" + s.panelName() + "\" slotunu aç."),
                Ui.label("2.  İçindekileri tümüyle sil (Ctrl+A, Delete)."),
                Ui.label("3.  Ctrl+V ile yapıştır ve kaydet."));
        HBox copied = new HBox(6, Icons.of("check", 16, "ok"), Ui.label("Panoya kopyalandı.", "muted"));
        copied.setAlignment(Pos.CENTER_LEFT);

        Button again = Ui.button("Yeniden kopyala", "copy");
        again.setOnAction(e -> toClipboard(s));
        Button next = Ui.button(last ? "Yükledim, bitir" : "Yükledim, sonraki", last ? "check" : "play", "primary");
        next.setOnAction(e -> {
            if (last) {
                int n = wizardQueue.size();
                wizardQueue = null;
                ctx.activity.note("ok", "Yükleme sihirbazı bitti",
                        n + " dosya panele yapıştırıldı. Canlıyla karşılaştırılıyor.");
                updateNotice();
                compare(null);
            } else {
                wizardIndex++;
                toClipboard(wizardQueue.get(wizardIndex));
                updateNotice();
            }
        });
        HBox buttons = new HBox(8, next, again);
        Label order = Ui.wrapped("Sıra: önce sayfa slotları, en son genel. Arada eski ve yeni kod bir arada çalışır; "
                + "bu beklenen bir durum.", "hint");
        VBox v = new VBox(10, top, file, slot, steps, copied, buttons, order);
        v.getStyleClass().add("wizard");
        return v;
    }

    // ---------------------------------------------------------------- step view

    /** One step of the prepare indicator: numbered dot, name, one line of result. */
    static final class StepView extends HBox {
        static final String PENDING = "pending";
        static final String RUNNING = "running";
        static final String OK = "ok";
        static final String FAIL = "fail";

        private final StackPane dot = new StackPane();
        private final Label sub;
        private final int number;

        StepView(int number, String name, String detail) {
            this.number = number;
            this.sub = Ui.label(detail, "small", "muted");
            dot.getStyleClass().add("step-dot");
            VBox text = new VBox(1, Ui.label(name, "step-name"), sub);
            getChildren().addAll(dot, text);
            getStyleClass().add("step");
            set(PENDING, detail);
        }

        void set(String state, String detail) {
            dot.getStyleClass().removeAll(RUNNING, OK, FAIL);
            if (!state.equals(PENDING)) {
                dot.getStyleClass().add(state);
            }
            switch (state) {
                case OK -> dot.getChildren().setAll(Icons.of("check", 14));
                case FAIL -> dot.getChildren().setAll(Icons.of("x", 14));
                default -> dot.getChildren().setAll(Ui.label(String.valueOf(number)));
            }
            sub.setText(detail);
        }
    }
}
