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
import javafx.geometry.Insets;
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
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Publish: prepare (backup, build, validate), see which files differ from the live site, paste them
 * in order with the upload wizard, compare again. Uploading is manual; the PlatinMarket API has no
 * endpoint for theme files.
 */
final class PublishPage implements Page {
    /** A backup older than this is refreshed (or knowingly skipped) before pasting. */
    private static final Duration BACKUP_FRESH = Duration.ofMinutes(120);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    record Row(Slot slot, int order, SlotRow status, Comparer.FileResult live) {}

    private final AppContext ctx;
    private final ScrollPane view;
    private final VBox content = new VBox(16);

    private final StatCard statBackup = new StatCard(T.t("stat.backup"));
    private final StatCard statLive = new StatCard(T.t("stat.live"));
    private final StatCard statBuild = new StatCard(T.t("stat.build"));
    private final StatCard statGit = new StatCard(T.t("stat.git"));

    private final StepView stepBackup = new StepView(1, T.t("step.backup"), T.t("step.backup.sub"));
    private final StepView stepBuild = new StepView(2, T.t("step.build"), T.t("step.build.sub"));
    private final StepView stepValidate = new StepView(3, T.t("step.validate"), T.t("step.validate.sub"));

    private final VBox notice = new VBox();
    private final TableView<Row> table = new TableView<>();
    private final VBox filesCard;
    private final VBox emptyCard;

    private List<Slot> wizardQueue;
    private int wizardIndex;

    PublishPage(AppContext ctx) {
        this.ctx = ctx;

        HBox stats = new HBox(12, statBackup, statLive, statBuild, statGit);

        Button prepare = ctx.guard(Ui.button(T.t("publish.prepare"), "play", "primary"));
        prepare.setOnAction(e -> prepare());
        Button compare = ctx.guard(Ui.button(T.t("publish.compare"), "compare"));
        compare.setOnAction(e -> compare());
        MenuButton single = new MenuButton(T.t("publish.single"));
        single.getStyleClass().add("btn");
        MenuItem onlyBackup = new MenuItem(T.t("publish.single.backup"));
        onlyBackup.setOnAction(e -> backup(null));
        MenuItem onlyBuild = new MenuItem(T.t("publish.single.build"));
        onlyBuild.setOnAction(e -> build(Builder.Mode.PANEL));
        MenuItem onlyValidate = new MenuItem(T.t("publish.single.validate"));
        onlyValidate.setOnAction(e -> validate());
        MenuItem readable = new MenuItem(T.t("publish.single.readable"));
        readable.setOnAction(e -> build(Builder.Mode.READABLE));
        single.getItems().addAll(onlyBackup, onlyBuild, onlyValidate, readable);
        ctx.guard(single);

        HBox steps = new HBox(14, stepBackup, line(), stepBuild, line(), stepValidate);
        steps.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, prepare, compare, single);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox prepareCard = Ui.card(Ui.cardHeader(T.t("publish.prepare.card"), T.t("publish.prepare.text")), steps, actions);

        buildTable();
        filesCard = Ui.card(Ui.cardHeader(T.t("publish.files"), T.t("publish.files.text")), table);
        Button go = Ui.button(T.t("publish.goSettings"), "settings", "primary");
        go.setOnAction(e -> ctx.navigator.accept("settings"));
        emptyCard = Ui.emptyState("folder", T.t("publish.empty.title"), T.t("publish.empty.text"), go);

        content.getChildren().addAll(stats, prepareCard, notice, filesCard);
        content.setPadding(new Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
    }

    private static Region line() {
        Region r = new Region();
        r.getStyleClass().add("step-line");
        return r;
    }

    @Override public String id() { return "publish"; }
    @Override public String title() { return T.t("publish.title"); }
    @Override public String subtitle() { return T.t("publish.subtitle"); }
    @Override public String icon() { return "publish"; }
    @Override public Node view() { return view; }

    // ------------------------------------------------------------------ table

    private void buildTable() {
        table.getColumns().add(textCol("#", 36, r -> r.order() > 0 ? String.valueOf(r.order()) : ""));
        TableColumn<Row, String> file = textCol(T.t("col.file"), 150, r -> r.slot().outputName());
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
        table.getColumns().add(textCol(T.t("col.slot"), 130, r -> r.slot().panelName()));
        table.getColumns().add(textCol(T.t("col.size"), 80, r -> r.status().outputChars() < 0 ? "-" : Ui.number(r.status().outputChars())));
        table.getColumns().add(nodeCol(T.t("col.target"), 120, r -> {
            if (r.status().outputChars() < 0) {
                return Ui.label("-", "muted");
            }
            ProgressBar bar = new ProgressBar(Math.min(1.0, r.status().percent() / 100.0));
            bar.setPrefWidth(52);
            bar.setMinWidth(52);
            if (r.status().percent() > 100) bar.getStyleClass().add("over");
            HBox h = new HBox(8, bar, Ui.label(Ui.percent(r.status().percent()), "small", "muted"));
            h.setAlignment(Pos.CENTER_LEFT);
            return h;
        }));
        table.getColumns().add(nodeCol(T.t("col.state"), 110, r -> switch (r.status().state()) {
            case NO_OUTPUT -> Ui.chip(T.t("chip.notBuilt"), "warn");
            case STALE -> Ui.chip(T.t("chip.stale"), "warn");
            case OVER -> Ui.chip(T.t("chip.over"), "warn");
            case OK -> Ui.chip(T.t("chip.ready"), "ok");
            default -> Ui.chip(T.t("chip.empty"), "");
        }));
        table.getColumns().add(nodeCol(T.t("col.live"), 110, r -> {
            if (ctx.compareRunning) return Ui.chip(T.t("chip.checking"), "");
            if (r.live() == null) return Ui.chip(ctx.lastCompare == null ? T.t("chip.notChecked") : "-", "");
            return switch (r.live().state()) {
                case SAME -> Ui.chip(T.t("chip.same"), "ok");
                case DIFFERENT -> differentCell(r);
                case UNREADABLE -> Ui.chip(T.t("chip.unreadable"), "err");
                case LOCAL_MISSING -> Ui.chip(T.t("chip.localMissing"), "");
            };
        }));
        table.setRowFactory(tv -> {
            TableRow<Row> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && row.getItem() != null && hasDiff(row.getItem())) {
                    DiffView.show(ctx, row.getItem().live());
                }
            });
            return row;
        });
        TableColumn<Row, Row> copy = nodeCol("", 52, r -> {
            if (r.status().outputChars() < 0) return new Region();
            Button b = Ui.iconButton("copy", T.t("copy.tip", r.slot().outputName()));
            b.disableProperty().bind(ctx.busy);
            b.setOnAction(e -> copyWithChecks(r));
            return b;
        });
        copy.setMinWidth(52);
        copy.setMaxWidth(52);
        table.getColumns().add(copy);
        // columns that hold chips, bars and buttons never get narrower than their content; the slot name gives way
        double[] mins = {30, 116, 76, 58, 100, 82, 94, 52};
        for (int i = 0; i < mins.length && i < table.getColumns().size(); i++) {
            table.getColumns().get(i).setMinWidth(mins[i]);
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(Ui.label(T.t("table.empty"), "muted"));
        table.setFixedCellSize(40);
        table.prefHeightProperty().bind(table.fixedCellSizeProperty().multiply(
                javafx.beans.binding.Bindings.size(table.getItems()).add(1.4)));
        table.setMinHeight(140);
    }

    /** The "different" chip, followed by the button that opens the line difference. */
    private Node differentCell(Row r) {
        Label chip = Ui.chip(T.t("chip.different"), "warn");
        if (!hasDiff(r)) {
            return chip;
        }
        Button eye = Ui.iconButton("eye", T.t("diff.tip", r.slot().outputName()));
        eye.setOnAction(e -> DiffView.show(ctx, r.live()));
        HBox h = new HBox(4, chip, eye);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** The file differs from the live one and there is a line-by-line view of it. */
    private static boolean hasDiff(Row r) {
        return r.live() != null && r.live().state() == LiveState.DIFFERENT && !r.live().lines().isEmpty();
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
            ErrorLog.write("status", e);
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
                statBackup.set("err", T.t("stat.none"), T.t("stat.backup.noneSub"));
            } else if (age.get().toHours() < 24) {
                statBackup.set("ok", Ages.describe(age.get()), latestStamp());
            } else if (age.get().toDays() < 7) {
                statBackup.set("warn", Ages.describe(age.get()), T.t("stat.backup.refresh"));
            } else {
                statBackup.set("err", Ages.describe(age.get()), T.t("stat.backup.old"));
            }
        } catch (IOException e) {
            statBackup.set("err", T.t("stat.unreadable"), e.getMessage());
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
            statLive.set("", T.t("stat.live.notSet"), T.t("stat.live.notSetSub"));
        } else if (ctx.compareRunning) {
            statLive.set("", T.t("stat.live.checking"), T.t("stat.live.checkingSub"));
        } else if (ctx.lastCompare == null) {
            statLive.set("", T.t("stat.live.notChecked"), T.t("stat.live.notCheckedSub"));
        } else {
            Comparer.Report r = ctx.lastCompare;
            String when = ctx.lastCompareTime == null ? "" : ctx.lastCompareTime.format(HM);
            long diff = r.count(LiveState.DIFFERENT);
            long bad = r.count(LiveState.UNREADABLE);
            if (bad > 0 && diff == 0) {
                statLive.set("err", T.t("stat.unreadable"), T.t("stat.live.unreadableSub", bad, when));
            } else if (diff > 0) {
                statLive.set("warn", T.t("stat.live.different", diff), T.t("stat.live.differentSub", when));
            } else {
                statLive.set("ok", T.t("stat.live.same", r.count(LiveState.SAME), r.files().size()), T.t("stat.live.sameSub", when));
            }
        }
    }

    private void updateBuildStat(List<Row> rows) {
        long stale = rows.stream().filter(r -> r.status().state() == State.STALE || r.status().state() == State.NO_OUTPUT).count();
        long over = rows.stream().filter(r -> r.status().state() == State.OVER).count();
        if (ctx.lastBuildFailure != null) {
            statBuild.set("err", T.t("stat.build.error"), ctx.lastBuildFailure.problem().code() + " " + T.title(ctx.lastBuildFailure.problem()));
        } else if (rows.isEmpty()) {
            statBuild.set("", T.t("stat.build.noSources"), T.t("stat.build.noSourcesSub"));
        } else if (stale > 0) {
            statBuild.set("warn", T.t("stat.build.stale"), T.t("stat.build.staleSub", stale));
        } else if (over > 0) {
            statBuild.set("warn", T.t("stat.build.current"), T.t("stat.build.overSub", over));
        } else {
            statBuild.set("ok", T.t("stat.build.current"), T.t("stat.build.currentSub"));
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
                text = T.t("stat.git.noRepo");
                sub = T.t("stat.git.noRepoSub");
            } else {
                try {
                    GitRepo.Status s = git.status();
                    if (!s.changes().isEmpty()) {
                        kind = "info";
                        text = T.t("stat.git.changes", s.changes().size());
                        sub = T.t("stat.git.changesSub", s.branch());
                    } else if (s.ahead() > 0) {
                        kind = "warn";
                        text = T.t("stat.git.ahead", s.ahead());
                        sub = T.t("stat.git.aheadSub");
                    } else {
                        kind = "ok";
                        text = T.t("stat.git.clean");
                        sub = s.branch();
                    }
                } catch (IOException e) {
                    kind = "err";
                    text = T.t("stat.unreadable");
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
            Button start = ctx.guard(Ui.button(T.t("banner.startWizard"), "upload", "primary"));
            start.setOnAction(e -> startWizard());
            Label text = Ui.wrapped(T.t("banner.different", names.size(), String.join(", ", names)));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox banner = new HBox(12, Icons.of("alert", 20, "warn"), text, start);
            banner.getStyleClass().addAll("banner", "warn");
            notice.getChildren().setAll(banner);
        } else if (ctx.lastCompare != null && ctx.lastCompare.allSame() && !ctx.lastCompare.files().isEmpty()) {
            HBox banner = new HBox(12, Icons.of("check", 20, "ok"), Ui.wrapped(T.t("banner.same")));
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
        stepBackup.set(StepView.RUNNING, T.t("step.backup.running"));
        stepBuild.set(StepView.PENDING, T.t("step.build.sub"));
        stepValidate.set(StepView.PENDING, T.t("step.validate.sub"));
        ctx.run(T.t("job.prepare"), out -> Workflow.prepare(ctx.cfg, out), (e, r) -> {
            showBackupStep(r.backup());
            showBuildStep(r.build());
            showValidateStep(r.validation());
            ctx.lastBuildFailure = r.failedAt() == Workflow.Step.BUILD || r.failedAt() == Workflow.Step.VALIDATE ? r.failure() : null;
            if (r.ok()) {
                List<String> order = Workflow.pasteOrder(ctx.cfg).stream().map(Slot::outputName).toList();
                List<Finding> warnings = new ArrayList<>(r.build().warnings());
                warnings.addAll(r.validation().allWarnings().stream().filter(f -> f.problem() != Problem.SLOT_OVER_TARGET).toList());
                e.done(warnings.isEmpty() ? "ok" : "warn", T.t("prepare.ok", String.join(" → ", order)), warnings);
                Platform.runLater(this::compare);
            } else {
                List<Finding> problems = new ArrayList<>();
                if (r.validation() != null) {
                    problems.addAll(r.validation().allErrors());
                } else if (r.failure() != null) {
                    problems.add(r.failure());
                }
                e.done("err", T.t(switch (r.failedAt()) {
                    case BACKUP -> "prepare.fail.backup";
                    case BUILD -> "prepare.fail.build";
                    case VALIDATE -> "prepare.fail.validate";
                }), problems);
            }
        });
    }

    private void showBackupStep(Backup.Report b) {
        if (b == null) {
            stepBackup.set(StepView.FAIL, T.t("step.backup.noCdn"));
        } else if (b.ok()) {
            stepBackup.set(StepView.OK, T.t("step.backup.ok", b.saved()));
        } else {
            stepBackup.set(StepView.FAIL, T.t("step.backup.fail", b.failed()));
        }
    }

    private void showBuildStep(Builder.Report b) {
        if (b == null) {
            stepBuild.set(StepView.PENDING, T.t("step.notRun"));
        } else if (b.ok()) {
            stepBuild.set(StepView.OK, T.t("step.build.ok", b.outputs().size(), Ui.number(b.total())));
        } else {
            stepBuild.set(StepView.FAIL, b.failure().problem().code() + " " + T.title(b.failure().problem()));
        }
    }

    private void showValidateStep(PanelValidator.Report v) {
        if (v == null) {
            stepValidate.set(StepView.PENDING, T.t("step.notRun"));
        } else if (v.ok()) {
            stepValidate.set(StepView.OK, T.t("step.validate.ok"));
        } else {
            stepValidate.set(StepView.FAIL, T.t("step.validate.fail", v.allErrors().size()));
        }
    }

    private void backup(Runnable then) {
        if (!ctx.requireCdn()) {
            return;
        }
        stepBackup.set(StepView.RUNNING, T.t("step.backup.running"));
        ctx.run(T.t("job.backup"), out -> new Backup(ctx.cfg, out).run(ctx.cdn().orElseThrow()), (e, r) -> {
            showBackupStep(r);
            if (r.ok()) {
                e.done("ok", T.t("backup.ok", r.saved(), r.stamp()), List.of());
                if (then != null) {
                    Platform.runLater(then);
                }
            } else {
                e.done("err", T.t("backup.fail"), List.of(r.failure()));
            }
        });
    }

    private void build(Builder.Mode mode) {
        if (mode == Builder.Mode.PANEL) {
            stepBuild.set(StepView.RUNNING, T.t("step.build.sub"));
        }
        ctx.run(T.t(mode == Builder.Mode.PANEL ? "job.build" : "job.readable"),
                out -> new Builder(ctx.cfg, out).build(mode, List.of()), (e, r) -> {
            if (mode == Builder.Mode.PANEL) {
                showBuildStep(r);
                ctx.lastBuildFailure = r.ok() ? null : r.failure();
            }
            if (r.ok()) {
                e.done(r.warnings().isEmpty() ? "ok" : "warn", T.t("build.ok", r.outputs().size(), Ui.number(r.total()),
                        Ui.number(r.budget()), ctx.cfg.rel(r.target())), r.warnings());
            } else {
                e.done("err", T.t("build.fail"), List.of(r.failure()));
            }
        });
    }

    private void validate() {
        stepValidate.set(StepView.RUNNING, T.t("step.validate.sub"));
        ctx.run(T.t("job.validate"), out -> new PanelValidator(ctx.cfg, out).run(), (e, r) -> {
            showValidateStep(r);
            List<Finding> all = new ArrayList<>(r.allErrors());
            all.addAll(r.allWarnings());
            e.done(r.ok() ? (r.allWarnings().isEmpty() ? "ok" : "warn") : "err",
                    r.ok() ? T.t("validate.ok") : T.t("validate.fail", r.allErrors().size()), all);
        });
    }

    private void compare() {
        if (!ctx.requireCdn()) {
            return;
        }
        ctx.run(T.t("job.compare"), out -> new Comparer(ctx.cfg, out).run(ctx.cdn().orElseThrow(), ctx.cfg.outputDir()),
                (e, r) -> {
                    ctx.lastCompare = r;
                    ctx.lastCompareTime = LocalDateTime.now();
                    List<Finding> f = liveFindings(r);
                    long diff = r.count(LiveState.DIFFERENT);
                    if (r.allSame()) {
                        e.done("ok", T.t("compare.same", r.files().size()), f);
                    } else if (r.count(LiveState.UNREADABLE) > 0) {
                        e.done("err", T.t("compare.unreadable", r.count(LiveState.UNREADABLE), diff), f);
                    } else {
                        e.done("warn", T.t("compare.different", diff), f);
                    }
                });
    }

    static List<Finding> liveFindings(Comparer.Report r) {
        List<Finding> out = new ArrayList<>();
        for (Comparer.FileResult f : r.files()) {
            switch (f.state()) {
                case DIFFERENT -> out.add(Finding.of(Problem.LIVE_DIFFERENT, f.name(),
                        T.t("live.diffDetail", Ui.number(f.liveChars()), Ui.number(f.localChars()), f.added(), f.removed())));
                case UNREADABLE -> out.add(Finding.of(Problem.CDN_UNREADABLE, f.name(), ""));
                case LOCAL_MISSING -> out.add(Finding.of(Problem.LOCAL_MISSING, f.name(), T.t("live.liveChars", Ui.number(f.liveChars()))));
                default -> { }
            }
        }
        return out;
    }

    /** Quiet comparison at start-up and on F5 (no card), like the Python window did. */
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
                ErrorLog.write("compare at start", e);
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
            warnings.add(T.t("copy.stale"));
        }
        if (r.status().state() == State.OVER) {
            warnings.add(T.t("copy.over"));
        }
        if (!warnings.isEmpty() && !Ui.confirm(ctx.stage, T.t("copy.confirm.header", r.slot().outputName()),
                String.join("\n", warnings), T.t("copy.continue"))) {
            return;
        }
        ensureFreshBackup(() -> {
            if (toClipboard(r.slot())) {
                ctx.activity.note("ok", T.t("copy.done.title", r.slot().outputName()), T.t("copy.done.text", r.slot().panelName()));
            }
        });
    }

    /** A backup older than two hours is refreshed (or knowingly skipped) before pasting. */
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
        String when = age.map(a -> T.t("backupOld.last", Ages.describe(a))).orElse(T.t("backupOld.none"));
        int c = Ui.choose(ctx.stage, T.t("backupOld.header"), T.t("backupOld.text", when),
                List.of(T.t("backupOld.take"), T.t("backupOld.skip"), T.t("common.cancel")));
        if (c == 0) {
            backup(proceed);
        } else if (c == 1) {
            proceed.run();
        }
    }

    private boolean toClipboard(Slot slot) {
        Path file = Workflow.output(ctx.cfg, slot);
        try {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(Files.readString(file, StandardCharsets.UTF_8));
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            return true;
        } catch (IOException e) {
            ctx.activity.note("err", T.t("copy.failed"), file + ": " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- wizard

    private void startWizard() {
        if (ctx.lastCompare == null) {
            Ui.info(ctx.stage, T.t("wizard.needCompare.header"), T.t("wizard.needCompare.text"));
            return;
        }
        List<Slot> queue = Workflow.pasteOrder(ctx.cfg).stream()
                .filter(s -> ctx.lastCompare.get(s.outputName()).map(f -> f.state() == LiveState.DIFFERENT).orElse(false))
                .toList();
        if (queue.isEmpty()) {
            Ui.info(ctx.stage, T.t("wizard.nothing.header"), T.t("wizard.nothing.text"));
            return;
        }
        boolean stale = table.getItems().stream().anyMatch(r -> r.status().state() == State.STALE);
        if (stale && !Ui.confirm(ctx.stage, T.t("wizard.stale.header"), T.t("wizard.stale.text"), T.t("wizard.stale.go"))) {
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
        Button cancel = Ui.button(T.t("wizard.cancel"), "x", "ghost", "small");
        cancel.setOnAction(e -> {
            ctx.activity.note("warn", T.t("wizard.cancelled.title"), T.t("wizard.cancelled.text", wizardIndex, wizardQueue.size()));
            wizardQueue = null;
            updateNotice();
        });
        HBox top = new HBox(10, Ui.chip(T.t("wizard.step", wizardIndex + 1, wizardQueue.size()), "info"),
                Ui.label(T.t("wizard.title"), "section-title"), Ui.spacer(), cancel);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox steps = new VBox(4,
                Ui.label(T.t("wizard.do1", s.panelName())),
                Ui.label(T.t("wizard.do2")),
                Ui.label(T.t("wizard.do3")));
        HBox copied = new HBox(6, Icons.of("check", 16, "ok"), Ui.label(T.t("wizard.copied"), "muted"));
        copied.setAlignment(Pos.CENTER_LEFT);

        Button again = Ui.button(T.t("wizard.again"), "copy");
        again.setOnAction(e -> toClipboard(s));
        Button next = Ui.button(T.t(last ? "wizard.finish" : "wizard.next"), last ? "check" : "play", "primary");
        next.setOnAction(e -> {
            if (last) {
                int n = wizardQueue.size();
                wizardQueue = null;
                ctx.activity.note("ok", T.t("wizard.done.title"), T.t("wizard.done.text", n));
                updateNotice();
                compare();
            } else {
                wizardIndex++;
                toClipboard(wizardQueue.get(wizardIndex));
                updateNotice();
            }
        });
        VBox v = new VBox(10, top, Ui.label(s.outputName(), "wizard-file"), Ui.label(T.t("wizard.slot", s.panelName()), "strong"),
                steps, copied, new HBox(8, next, again), Ui.wrapped(T.t("wizard.order"), "hint"));
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
            getChildren().addAll(dot, new VBox(1, Ui.label(name, "step-name"), sub));
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
