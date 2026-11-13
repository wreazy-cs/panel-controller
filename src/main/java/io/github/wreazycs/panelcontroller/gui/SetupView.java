package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.StoreFinder;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.application.Platform;
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
 *
 * <p>The CDN address is found from the store's own address. When the store is closed to visitors
 * (B2B) or the page has no theme links, the address is typed by hand.
 */
final class SetupView {
    private final AppContext ctx;
    private final Runnable onReady;
    private final ScrollPane view;

    private final Label folderState = Ui.chip("", "");
    private final Label folderPath = Ui.wrapped("", "mono", "small");
    private final Label cdnState = Ui.chip("", "");
    private final TextField site = new TextField();
    private final Button find = Ui.button(T.t("setup.site.find"), "search", "primary");
    private final TextField cdn = new TextField();
    private final Button save = Ui.button(T.t("setup.cdn.save"), "check");
    private final Button manualToggle = Ui.button(T.t("setup.manual.show"), null, "ghost", "small");
    private final VBox manual;
    private final Label message = Ui.wrapped("", "small");
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

        site.setPromptText(T.t("setup.site.prompt"));
        HBox.setHgrow(site, Priority.ALWAYS);
        site.setOnAction(e -> findCdn());
        find.setOnAction(e -> findCdn());
        HBox siteRow = new HBox(8, site, find);
        siteRow.setAlignment(Pos.CENTER_LEFT);

        cdn.setPromptText("https://platincdn.com/<no>/tema/%s");
        HBox.setHgrow(cdn, Priority.ALWAYS);
        cdn.setOnAction(e -> verifyAndSave(cdn.getText().strip(), false));
        save.setOnAction(e -> verifyAndSave(cdn.getText().strip(), false));
        HBox cdnRow = new HBox(8, cdn, save);
        cdnRow.setAlignment(Pos.CENTER_LEFT);
        manual = new VBox(8, Ui.wrapped(T.t("setup.manual.text"), "muted"), cdnRow);
        showManual(false);
        result("", false);
        manualToggle.setOnAction(e -> showManual(!manual.isVisible()));

        cdnStep = step("2", T.t("setup.cdn"), cdnState, T.t("setup.cdn.text"), siteRow, message,
                new HBox(manualToggle), manual);

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

    private void showManual(boolean show) {
        manual.setVisible(show);
        manual.setManaged(show);
        manualToggle.setText(T.t(show ? "setup.manual.hide" : "setup.manual.show"));
    }

    // ---------------------------------------------------------------- find from the store address

    private void findCdn() {
        String address = site.getText().strip();
        if (StoreFinder.normalize(address).isEmpty()) {
            result(T.t("setup.site.invalid"), true);
            return;
        }
        result(T.t("setup.site.looking"), false);
        find.setDisable(true);
        ctx.background.submit(() -> {
            StoreFinder.Result r = StoreFinder.find(address);
            Platform.runLater(() -> {
                find.setDisable(false);
                found(r);
            });
        });
    }

    private void found(StoreFinder.Result r) {
        switch (r.outcome()) {
            case FOUND -> {
                cdn.setText(r.cdn());
                verifyAndSave(r.cdn(), true);
            }
            case CLOSED -> byHand(T.t("setup.site.closed", r.status()));
            case NOT_FOUND -> byHand(T.t("setup.site.notFound"));
            case HTTP_ERROR -> byHand(T.t("setup.site.http", r.status()));
            case UNREACHABLE -> byHand(T.t("setup.site.unreachable"));
            case INVALID -> result(T.t("setup.site.invalid"), true);
        }
    }

    /** The address could not be found by itself: say why and open the field for typing it. */
    private void byHand(String why) {
        result(why, true);
        showManual(true);
        cdn.requestFocus();
    }

    // ---------------------------------------------------------------- save

    /** The address is saved only after the first panel file could be read from it. */
    private void verifyAndSave(String address, boolean foundByApp) {
        if (!AppContext.validCdn(address)) {
            result(T.t("settings.cdn.invalid"), true);
            showManual(true);
            return;
        }
        result(T.t("settings.cdn.testing", ctx.firstFile()), false);
        save.setDisable(true);
        find.setDisable(true);
        ctx.testCdn(address, r -> {
            save.setDisable(false);
            find.setDisable(false);
            if (r.isEmpty()) {
                if (foundByApp) {
                    byHand(T.t("setup.site.foundUnreadable", address));
                } else {
                    result(T.t("setup.cdn.fail"), true);
                }
                return;
            }
            ctx.settings.cdn = address;
            ctx.settingsChanged();
            ctx.activity.note("ok", T.t("setup.cdn.saved"), address);
            result("", false);
            onReady.run();
        });
    }

    private void result(String text, boolean error) {
        message.setText(text);
        message.getStyleClass().setAll("label", error ? "error-text" : "muted", "small");
        message.setVisible(!text.isEmpty());
        message.setManaged(!text.isEmpty());
    }
}
