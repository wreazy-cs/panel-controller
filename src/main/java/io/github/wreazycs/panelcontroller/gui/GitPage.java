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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Git: choose the changed files, write a short message, commit; push only when build and validation
 * are clean. Commits go as "... -p" with a link to Panel Controller.
 */
final class GitPage implements Page {

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
        TableColumn<Item, Item> state = new TableColumn<>(T.t("git.col.state"));
        state.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        state.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Item it, boolean empty) {
                super.updateItem(it, empty);
                setText(null);
                if (empty || it == null) {
                    setGraphic(null);
                    return;
                }
                String code = it.change.code().trim();
                setGraphic(Ui.chip(label(code), code.equals("??") ? "info" : code.contains("D") ? "err" : "warn"));
            }
        });
        state.setPrefWidth(170);
        state.setMinWidth(170);
        state.setMaxWidth(170);
        state.setSortable(false);
        TableColumn<Item, String> path = new TableColumn<>(T.t("git.col.file"));
        path.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().change.path()));
        path.setSortable(false);
        table.getColumns().addAll(List.of(pick, state, path));
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label(T.t("git.empty"), "muted"));
        table.setPrefHeight(260);

        Button all = Ui.button(T.t("git.all"), null, "small");
        all.setOnAction(e -> table.getItems().forEach(i -> i.selected.set(true)));
        Button none = Ui.button(T.t("git.none"), null, "small");
        none.setOnAction(e -> table.getItems().forEach(i -> i.selected.set(false)));
        Button reload = Ui.iconButton("refresh", T.t("git.refresh"));
        reload.setOnAction(e -> refresh());

        message.setPromptText(T.t("git.message.prompt"));
        message.textProperty().addListener((o, a, b) -> updatePreview());
        HBox.setHgrow(message, Priority.ALWAYS);
        Button commit = ctx.guard(Ui.button(T.t("git.commit"), "check", "primary"));
        commit.setOnAction(e -> commit());
        Button push = ctx.guard(Ui.button(T.t("git.push"), "upload"));
        push.setOnAction(e -> push());

        VBox changes = Ui.card(Ui.cardHeader(T.t("git.changes"), T.t("git.changes.sub"), all, none, reload), table);
        VBox send = Ui.card(Ui.cardHeader(T.t("git.send"), T.t("git.send.sub")), new HBox(8, message, commit, push), preview);
        VBox log = Ui.card(Ui.cardHeader(T.t("git.history"), null), history);
        HBox head = new HBox(10, Icons.of("git", 20), branch, chips);
        head.setAlignment(Pos.CENTER_LEFT);
        repoBody = new VBox(16, head, changes, send, log);
        notRepo = Ui.emptyState("git", T.t("git.notRepo.title"), T.t("git.notRepo.text", Problem.NOT_A_REPO.code()));

        content.getChildren().add(repoBody);
        content.setPadding(new Insets(4, 24, 24, 24));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
        updatePreview();
    }

    @Override public String id() { return "git"; }
    @Override public String title() { return T.t("git.title"); }
    @Override public String subtitle() { return T.t("git.subtitle"); }
    @Override public String icon() { return "git"; }
    @Override public Node view() { return view; }

    /** Two-letter git status in words. */
    static String label(String code) {
        if (code.equals("??")) return T.t("change.new");
        if (code.contains("D")) return T.t("change.deleted");
        if (code.contains("R")) return T.t("change.renamed");
        if (code.contains("A")) return T.t("change.added");
        if (code.contains("U")) return T.t("change.conflict");
        return T.t("change.modified");
    }

    private void updatePreview() {
        String m = message.getText().isBlank() ? "<…>" : message.getText();
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
                    branch.setText(T.t("git.unreadable", e.getMessage()));
                });
            }
        });
    }

    private void show(GitRepo.Status s, List<String> log) {
        content.getChildren().setAll(repoBody);
        branch.setText(T.t("git.branch", s.branch()));
        chips.getChildren().clear();
        if (s.ahead() > 0) chips.getChildren().add(Ui.chip(T.t("git.ahead", s.ahead()), "warn"));
        if (s.behind() > 0) chips.getChildren().add(Ui.chip(T.t("git.behind", s.behind()), "info"));
        if (s.ahead() == 0 && s.behind() == 0) chips.getChildren().add(Ui.chip(T.t("git.even"), "ok"));
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
            history.getChildren().add(Ui.label(T.t("git.noHistory"), "muted"));
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
            ctx.activity.start(T.t("job.commit")).done("err", T.title(p), List.of(Finding.of(p, "", "")));
            return;
        }
        String list = String.join("\n", paths.stream().limit(12).toList())
                + (paths.size() > 12 ? "\n" + T.t("git.commit.more", paths.size() - 12) : "");
        if (!Ui.confirm(ctx.stage, T.t("git.commit.confirm", paths.size()),
                GitRepo.commitSubject(msg, LocalDateTime.now()) + "\n\n" + list, T.t("git.commit"))) {
            return;
        }
        GitRepo git = new GitRepo(ctx.cfg.root());
        ctx.run(T.t("job.commit"), out -> {
            GitRepo.Result r = git.commit(paths, msg);
            out.print(r.output());
            return r;
        }, (e, r) -> {
            if (r.ok()) {
                message.clear();
                e.done("ok", T.t("git.commit.ok", paths.size()), List.of());
            } else {
                e.done("err", T.t("git.commit.fail"), r.failure() == null ? List.of() : List.of(r.failure()));
            }
        });
    }

    /** Build + validate first; push only when both are clean. */
    private void push() {
        if (!Ui.confirm(ctx.stage, T.t("git.push.confirm"), T.t("git.push.text"), T.t("git.push"))) {
            return;
        }
        GitRepo git = new GitRepo(ctx.cfg.root());
        ctx.run(T.t("job.push"), out -> {
            out.println(">>> push: build + validate");
            Builder.Report b = new Builder(ctx.cfg, out).build(Builder.Mode.PANEL, List.of());
            if (!b.ok()) {
                return new GitRepo.Result(false, "", Finding.of(Problem.PUSH_BLOCKED, "", b.failure().line()));
            }
            PanelValidator.Report v = new PanelValidator(ctx.cfg, out).run();
            if (!v.ok()) {
                Finding first = v.allErrors().isEmpty() ? null : v.allErrors().get(0);
                return new GitRepo.Result(false, "", Finding.of(Problem.PUSH_BLOCKED, "", first == null ? "" : first.line()));
            }
            GitRepo.Result r = git.push();
            out.print(r.output());
            return r;
        }, (e, r) -> {
            if (r.ok()) {
                e.done("ok", T.t("git.push.ok"), List.of());
            } else {
                e.done("err", T.t("git.push.fail"), r.failure() == null ? List.of() : List.of(r.failure()));
            }
        });
    }
}
