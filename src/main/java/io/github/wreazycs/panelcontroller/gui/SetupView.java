package io.github.wreazycs.panelcontroller.gui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

/**
 * Shown instead of Publish, Restore and Git until the two required settings are in place:
 * a project folder with kaynak/ and a working store CDN address.
 */
final class SetupView {
    private final AppContext ctx;
    private final Runnable onReady;
    private final ScrollPane view;

    private final Label folderState = Ui.chip("", "");
    private final Label folderPath = Ui.wrapped("", "mono", "small");
    private final Label cdnState = Ui.chip("", "");
    private final TextField cdn = new TextField();
    private final Button save = Ui.button(T.t("setup.cdn.save"), "check", "primary");
    private final Label cdnResult = Ui.wrapped("", "small");
    private final VBox cdnStep;

    SetupView(AppContext ctx, Consumer<Path> projectChooser, Runnable onReady) {
        this.ctx = ctx;
        this.onReady = onReady;

        Button choose = Ui.button(T.t("settings.chooseFolder"), "folder");
        choose.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle(T.t("settings.chooseFolder.title"));
            if (Files.isDirectory(ctx.cfg.root())) dc.setInitialDirectory(ctx.cfg.root().toFile());
            File f = dc.showDialog(ctx.stage);
            if (f != null) projectChooser.accept(f.toPath());
        });
        VBox folderStep = step("1", T.t("setup.folder"), folderState, T.t("setup.folder.text"),
                folderPath, new HBox(choose));

        cdn.setPromptText("https://platincdn.com/<no>/tema/%s");
        HBox.setHgrow(cdn, Priority.ALWAYS);
        cdn.setOnAction(e -> testAndSave());
        save.setOnAction(e -> testAndSave());
        HBox cdnRow = new HBox(8, cdn, save);
        cdnRow.setAlignment(Pos.CENTER_LEFT);
        cdnStep = step("2", T.t("setup.cdn"), cdnState, T.t("setup.cdn.text"), cdnRow, cdnResult);

        VBox card = Ui.card(Ui.cardHeader(T.t("setup.header"), T.t("setup.header.sub")), folderStep, cdnStep);
        VBox content = new VBox(16, card);
        content.setPadding(new Insets(4, 24, 24, 24));
        content.setMaxWidth(860);
        view = new ScrollPane(content);
        view.setFitToWidth(true);
    }

    private static VBox step(String number, String title, Label state, String text, Node... body) {
        Label n = Ui.chip(number, "info");
        HBox head = new HBox(10, n, Ui.label(title, "strong"), state);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox v = new VBox(8, head, Ui.wrapped(text, "muted"));
        v.getChildren().addAll(body);
        v.setPadding(new Insets(12, 0, 4, 0));
        return v;
    }

    Node view() {
        return view;
    }

    void refresh() {
        boolean folderOk = ctx.hasSources();
        mark(folderState, folderOk);
        folderPath.setText(folderOk ? ctx.cfg.root().toString()
                : T.t("setup.folder.missing", ctx.cfg.root().toString()));
        mark(cdnState, ctx.hasCdn());
        cdnStep.setDisable(!folderOk);
        if (cdn.getText().isBlank() && ctx.cfg.cdnTemplate() != null) {
            cdn.setText(ctx.cfg.cdnTemplate());
        }
    }

    private static void mark(Label chip, boolean ok) {
        chip.setText(T.t(ok ? "setup.done" : "setup.required"));
        chip.getStyleClass().removeAll("ok", "err");
        chip.getStyleClass().add(ok ? "ok" : "err");
    }

    /** The address is saved only after the first panel file could be read from it. */
    private void testAndSave() {
        String c = cdn.getText().strip();
        if (!AppContext.validCdn(c)) {
            result(T.t("settings.cdn.invalid"), true);
            return;
        }
        String file = ctx.firstFile();
        result(T.t("settings.cdn.testing", file), false);
        save.setDisable(true);
        ctx.testCdn(c, r -> {
            save.setDisable(false);
            if (r.isEmpty()) {
                result(T.t("setup.cdn.fail"), true);
                return;
            }
            ctx.settings.cdn = c;
            ctx.settingsChanged();
            ctx.activity.note("ok", T.t("setup.cdn.saved"), c);
            result("", false);
            onReady.run();
        });
    }

    private void result(String text, boolean error) {
        cdnResult.setText(text);
        cdnResult.getStyleClass().setAll("label", error ? "error-text" : "muted", "small");
    }
}
