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
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

/** Settings: project folder and store settings, language, theme, system check (and the API key when API is on). */
final class SettingsPage implements Page {
    private final AppContext ctx;
    private final VaultUi vaultUi;
    private final Supplier<String> theme;
    private final ScrollPane view;

    private final Label folder = Ui.label("", "mono");
    private final TextField cdn = new TextField();
    private final TextField slotTarget = numberField();
    private final TextField budget = numberField();
    private final Label cdnResult = Ui.wrapped("", "small");
    private final ToggleButton light = new ToggleButton(T.t("settings.light"));
    private final ToggleButton dark = new ToggleButton(T.t("settings.dark"));
    private final ToggleButton tr = new ToggleButton("Türkçe");
    private final ToggleButton en = new ToggleButton("English");
    private final SystemCheckView systemView;
    private final Label keyState = Ui.label("", "strong");
    private boolean systemChecked;

    SettingsPage(AppContext ctx, VaultUi vaultUi, boolean showKeyCard, Consumer<Path> projectChooser,
                 Consumer<String> themeSetter, Supplier<String> theme, Runnable lockNow) {
        this.ctx = ctx;
        this.vaultUi = vaultUi;
        this.theme = theme;

        // ---- project
        Button choose = Ui.button(T.t("settings.chooseFolder"), "folder");
        choose.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle(T.t("settings.chooseFolder.title"));
            if (Files.isDirectory(ctx.cfg.root())) dc.setInitialDirectory(ctx.cfg.root().toFile());
            File f = dc.showDialog(ctx.stage);
            if (f != null) projectChooser.accept(f.toPath());
        });
        folder.setMinWidth(0);
        folder.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(folder, Priority.ALWAYS);
        HBox folderRow = new HBox(10, folder, choose);
        folderRow.setAlignment(Pos.CENTER_LEFT);

        cdn.setPromptText("https://platincdn.com/<no>/tema/%s");
        HBox.setHgrow(cdn, Priority.ALWAYS);
        Button test = Ui.button(T.t("settings.cdn.test"), "compare");
        test.setOnAction(e -> testCdn());
        Button save = Ui.button(T.t("settings.save"), "check", "primary");
        save.setOnAction(e -> save());

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, fields);
        grid.add(Ui.label(T.t("settings.cdn"), "strong"), 0, 0);
        grid.add(new HBox(8, cdn, test), 1, 0);
        grid.add(Ui.wrapped(T.t("settings.cdn.hint"), "hint"), 1, 1);
        grid.add(cdnResult, 1, 2);
        grid.add(Ui.label(T.t("settings.slotTarget"), "strong"), 0, 3);
        grid.add(new HBox(8, slotTarget, Ui.label(T.t("settings.slotTarget.hint"), "muted")), 1, 3);
        grid.add(Ui.label(T.t("settings.budget"), "strong"), 0, 4);
        grid.add(new HBox(8, budget, Ui.label(T.t("settings.budget.hint"), "muted")), 1, 4);
        VBox project = Ui.card(Ui.cardHeader(T.t("settings.project"), T.t("settings.project.sub")), folderRow, grid, new HBox(save));

        // ---- system check
        systemView = new SystemCheckView(ctx, () -> ctx.cfg.root());
        VBox system = Ui.card(Ui.cardHeader(T.t("settings.system"), T.t("settings.system.sub")), systemView);

        // ---- language
        ToggleGroup langs = new ToggleGroup();
        for (ToggleButton b : new ToggleButton[] {tr, en}) {
            b.setToggleGroup(langs);
            b.getStyleClass().add("btn");
        }
        (T.lang().equals("en") ? en : tr).setSelected(true);
        tr.setOnAction(e -> setLanguage("tr"));
        en.setOnAction(e -> setLanguage("en"));
        VBox language = Ui.card(Ui.cardHeader(T.t("settings.language"), T.t("settings.language.sub")), new HBox(8, tr, en));

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
        VBox appearance = Ui.card(Ui.cardHeader(T.t("settings.appearance"), T.t("settings.appearance.sub")), new HBox(8, light, dark));

        // ---- files
        Button errors = Ui.button(T.t("settings.errorLog"), "notes");
        errors.setOnAction(e -> {
            if (Files.exists(ErrorLog.file())) {
                ctx.open(ErrorLog.file());
            } else {
                Ui.info(ctx.stage, T.t("settings.errorLog.empty.header"), T.t("settings.errorLog.empty.text"));
            }
        });
        VBox files = Ui.card(Ui.cardHeader(T.t("settings.files"), T.t("settings.files.sub", ErrorLog.file())), new HBox(errors));

        VBox content = new VBox(16, project, system, language, appearance);
        if (showKeyCard) {
            Button key = Ui.button("API…", "key", "primary");
            key.setOnAction(e -> vaultUi.keyDialog());
            Button changePin = Ui.button("PIN…", "pen");
            changePin.setOnAction(e -> vaultUi.changePinDialog());
            Button removePin = Ui.button("PIN ×", "x", "danger");
            removePin.setOnAction(e -> vaultUi.removePinDialog());
            Button lock = Ui.button("Lock", "lock");
            lock.setOnAction(e -> lockNow.run());
            content.getChildren().add(Ui.card(Ui.cardHeader("API", null), keyState, new FlowPane(8, 8, key, changePin, removePin, lock)));
        }
        content.getChildren().add(files);
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
    @Override public String title() { return T.t("settings.title"); }
    @Override public String subtitle() { return T.t("settings.subtitle"); }
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
        if (!systemChecked) {
            systemChecked = true;
            systemView.check();
        }
    }

    private void setLanguage(String lang) {
        (lang.equals("en") ? en : tr).setSelected(true);
        if (lang.equals(ctx.prefs.get("lang", "tr"))) {
            return;
        }
        ctx.prefs.put("lang", lang);
        ctx.activity.note("ok", T.t("settings.language.changed"), T.t("settings.language.changedText"));
    }

    private void save() {
        String c = cdn.getText().strip();
        if (!c.isEmpty() && (!c.startsWith("https://") || !c.contains("%s"))) {
            cdnResult.setText(T.t("settings.cdn.invalid"));
            cdnResult.getStyleClass().setAll("label", "error-text", "small");
            return;
        }
        ctx.settings.cdn = c;
        ctx.settings.slotTarget = parse(slotTarget.getText(), ProjectSettings.DEFAULT_SLOT_TARGET);
        ctx.settings.budget = parse(budget.getText(), ProjectSettings.DEFAULT_BUDGET);
        ctx.settingsChanged();
        cdnResult.setText(T.t("settings.saved"));
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
            cdnResult.setText(T.t("settings.cdn.invalid"));
            cdnResult.getStyleClass().setAll("label", "error-text", "small");
            return;
        }
        Slot first = ctx.cfg.slots().get(0);
        cdnResult.setText(T.t("settings.cdn.testing", first.outputName()));
        cdnResult.getStyleClass().setAll("label", "muted", "small");
        ctx.background.submit(() -> {
            Optional<String> r = new Cdn(c).fetch(first.outputName());
            Platform.runLater(() -> {
                if (r.isPresent()) {
                    cdnResult.setText(T.t("settings.cdn.ok", first.outputName(), Ui.number(r.get().length())));
                    cdnResult.getStyleClass().setAll("label", "small");
                } else {
                    cdnResult.setText(T.t("settings.cdn.fail"));
                    cdnResult.getStyleClass().setAll("label", "error-text", "small");
                }
            });
        });
    }
}
