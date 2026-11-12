package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Builder;
import io.github.wreazycs.panelcontroller.Finding;
import io.github.wreazycs.panelcontroller.GitRepo;
import io.github.wreazycs.panelcontroller.PanelValidator;
import io.github.wreazycs.panelcontroller.Problem;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Git: choose the changed files, write a short message, commit; push only when build and validation
 * are clean (the Python rule). Commits go as "... -p" with a link to Panel Controller.
 */
final class GitPage implements Page {

    /** A changed file with its checkbox state. */
    static final class Item {
        final GitRepo.Change change;
        final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);

        Item(GitRepo.Change change) {
            this.change = change;
        }
    }

    private final AppContext ctx;
    private final ScrollPane view;
    private final VBox content = new VBox(16);
    private final Label branch = Ui.label("", "strong");
    private final HBox chips = new HBox(6);
    private final TableView<Item> table = new TableView<>();
    private final TextField message = new TextField();
    private final Label preview = Ui.label("", "mono", "small", "muted");
    private final VBox history = new VBox(4);
    private final VBox repoBody;
    private final VBox notRepo;

    GitPage(AppContext ctx) {
        this.ctx = ctx;

        TableColumn<Item, Boolean> pick = new TableColumn<>("");
        pick.setCellValueFactory(c -> c.getValue().selected);
        pick.setCellFactory(CheckBoxTableCell.forTableColumn(pick));
        pick.setPrefWidth(44);
        pick.setMinWidth(44);
        pick.setMaxWidth(44);
        pick.setSortable(false);
        TableColumn<Item, Item> state = new TableColumn<>("Durum");
        state.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        state.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(Item it, boolean empty) {
                super.updateItem(it, empty);
                setText(null);
                setGraphic(empty || it == null ? null : Ui.chip(it.change.label(),
                        it.change.code().trim().equals("??") ? "info" : it.change.code().contains("D") ? "err" : "warn"));
            }
        });
        state.setPrefWidth(170);
        state.setMinWidth(170);
        state.setMaxWidth(170);
        state.setSortable(false);
        TableColumn<Item, String> path = new TableColumn<>("Dosya");
        path.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().change.path()));
        path.setSortable(false);
        table.getColumns().addAll(List.of(pick, state, path));
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("Değişiklik yok: her şey commit edilmiş.", "muted"));
        table.setPrefHeight(260);

        Button all = Ui.button("Tümünü seç", null, "small");
        all.setOnAction(e -> table.getItems().forEach(i -> i.selected.set(true)));
        Button none = Ui.button("Hiçbiri", null, "small");
        none.setOnAction(e -> table.getItems().forEach(i -> i.selected.set(false)));
        Button reload = Ui.iconButton("refresh", "Yenile");
        reload.setOnAction(e -> refresh());

        message.setPromptText("Ne değişti? Örnek: sepet bildirimi düzeltildi");
        message.textProperty().addListener((o, a, b) -> updatePreview());
        HBox.setHgrow(message, Priority.ALWAYS);
        Button commit = ctx.guard(Ui.button("Commit et", "check", "primary"));
        commit.setOnAction(e -> commit());
        Button push = ctx.guard(Ui.button("Push et", "upload"));
        push.setOnAction(e -> push());

        VBox changes = Ui.card(
                Ui.cardHeader("Değişen dosyalar", "Commit'e girecekleri işaretle.", all, none, reload),
                table);
        VBox send = Ui.card(
                Ui.cardHeader("Commit ve push",
                        "Tarih, gün, saat ve -p (Panel Controller) eki otomatik eklenir. Push öncesi derle ve doğrula çalışır; "
                                + "temiz değilse push yapılmaz. Push, panele yüklendi demek değildir."),
                new HBox(8, message, commit, push), preview);
        VBox log = Ui.card(Ui.cardHeader("Son commit'ler", null), history);
        HBox head = new HBox(10, Icons.of("git", 20), branch, chips);
        head.setAlignment(Pos.CENTER_LEFT);
        repoBody = new VBox(16, head, changes, send, log);
        notRepo = Ui.emptyState("git", "Proje bir git deposu değil",
                "Commit ve push için proje klasörünün git ile izlenmesi gerekiyor (" + Problem.NOT_A_REPO.code() + ").");

        content.getChildren().add(repoBody);
        content.setPadding(new Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
        updatePreview();
    }

    @Override public String id() { return "git"; }
    @Override public String title() { return "Git"; }
    @Override public String subtitle() { return "Değişiklikleri kaydet (commit) ve GitHub'a gönder (push)."; }
    @Override public String icon() { return "git"; }
    @Override public Node view() { return view; }

    private void updatePreview() {
        String m = message.getText().isBlank() ? "<mesaj>" : message.getText();
        preview.setText(GitRepo.commitSubject(m, LocalDateTime.now()) + "\n" + GitRepo.commitBody());
    }

    @Override
    public void refresh() {
        updatePreview();
        GitRepo git = new GitRepo(ctx.cfg.root());
        ctx.background.submit(() -> {
            if (!git.hasGitDir()) {
                Platform.runLater(() -> content.getChildren().setAll(notRepo));
                return;
            }
            try {
                GitRepo.Status s = git.status();
                List<String> log = git.log(8);
                Platform.runLater(() -> show(s, log));
            } catch (IOException e) {
                Platform.runLater(() -> {
                    content.getChildren().setAll(repoBody);
                    branch.setText("git okunamadı: " + e.getMessage());
                });
            }
        });
    }

    private void show(GitRepo.Status s, List<String> log) {
        content.getChildren().setAll(repoBody);
        branch.setText("Dal: " + s.branch());
        chips.getChildren().clear();
        if (s.ahead() > 0) chips.getChildren().add(Ui.chip(s.ahead() + " push edilmemiş", "warn"));
        if (s.behind() > 0) chips.getChildren().add(Ui.chip(s.behind() + " uzakta yeni", "info"));
        if (s.ahead() == 0 && s.behind() == 0) chips.getChildren().add(Ui.chip("uzakla eşit", "ok"));
        List<String> previouslySelected = table.getItems().stream().filter(i -> i.selected.get()).map(i -> i.change.path()).toList();
        List<Item> items = new ArrayList<>();
        for (GitRepo.Change c : s.changes()) {
            Item it = new Item(c);
            it.selected.set(previouslySelected.contains(c.path()));
            items.add(it);
        }
        table.getItems().setAll(items);
        history.getChildren().clear();
        if (log.isEmpty()) {
            history.getChildren().add(Ui.label("Henüz commit yok.", "muted"));
        }
        for (String l : log) {
            history.getChildren().add(Ui.label(l, "mono", "small"));
        }
    }

    private void commit() {
        List<String> paths = table.getItems().stream().filter(i -> i.selected.get()).map(i -> i.change.path()).toList();
        String msg = message.getText().strip();
        if (paths.isEmpty() || msg.isEmpty()) {
            Problem p = paths.isEmpty() ? Problem.NOTHING_SELECTED : Problem.EMPTY_MESSAGE;
            ctx.activity.start("Commit").done("err", p.title(), List.of(Finding.of(p, "", "")));
            return;
        }
        if (!Ui.confirm(ctx.stage, paths.size() + " dosya commit edilsin mi?",
                GitRepo.commitSubject(msg, LocalDateTime.now()) + "\n\n" + String.join("\n", paths.stream().limit(12).toList())
                        + (paths.size() > 12 ? "\n… ve " + (paths.size() - 12) + " dosya daha" : ""), "Commit et")) {
            return;
        }
        GitRepo git = new GitRepo(ctx.cfg.root());
        ctx.run("Commit", out -> {
            GitRepo.Result r = git.commit(paths, msg);
            out.print(r.output());
            return r;
        }, (e, r) -> {
            if (r.ok()) {
                message.clear();
                e.done("ok", paths.size() + " dosya commit edildi. GitHub'a göndermek için Push et.", List.of());
            } else {
                e.done("err", "Commit yapılamadı.", r.failure() == null ? List.of() : List.of(r.failure()));
            }
        });
    }

    /** Python rule: build + validate first; push only when both are clean. */
    private void push() {
        if (!Ui.confirm(ctx.stage, "Push edilsin mi?",
                "Önce derle ve doğrula çalışır; temiz değilse push yapılmaz.\nPush, panele yüklendi demek değildir.", "Push et")) {
            return;
        }
        GitRepo git = new GitRepo(ctx.cfg.root());
        ctx.run("Push", out -> {
            out.println(">>> push oncesi: derle + dogrula");
            Builder.Report b = new Builder(ctx.cfg, out).build(Builder.Mode.PANEL, List.of());
            if (!b.ok()) {
                return new GitRepo.Result(false, "", Finding.of(Problem.PUSH_BLOCKED, "", "derleme: " + b.failure().line()));
            }
            PanelValidator.Report v = new PanelValidator(ctx.cfg, out).run();
            if (!v.ok()) {
                Finding first = v.allErrors().isEmpty() ? null : v.allErrors().get(0);
                return new GitRepo.Result(false, "", Finding.of(Problem.PUSH_BLOCKED, "",
                        "doğrulama: " + (first == null ? "" : first.line())));
            }
            GitRepo.Result r = git.push();
            out.print(r.output());
            return r;
        }, (e, r) -> {
            if (r.ok()) {
                e.done("ok", "Push edildi. Bu, panele yüklendi demek değil.", List.of());
            } else {
                e.done("err", "Push yapılmadı.", r.failure() == null ? List.of() : List.of(r.failure()));
            }
        });
    }
}
