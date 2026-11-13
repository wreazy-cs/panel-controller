package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.AppInfo;
import io.github.wreazycs.panelcontroller.Comparer;
import io.github.wreazycs.panelcontroller.vault.KeySession;
import io.github.wreazycs.panelcontroller.vault.KeyVault;
import io.github.wreazycs.panelcontroller.vault.Protector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** The desktop window: side menu, pages, activity panel, guide tab. */
public final class PanelApp extends Application {

    /**
     * Tasks, Tools and the API page (with the key and the lock screen) are kept in the code but not
     * shown: the PlatinMarket API has no theme endpoints, so for now the app checks and commits only.
     */
    static final boolean EXTRA_PAGES = false;

    /** -Dpanelcontroller.prefs=<name> keeps settings in a separate node (used by tests). */
    private final Preferences prefs = System.getProperty("panelcontroller.prefs", "").isBlank()
            ? Preferences.userNodeForPackage(PanelApp.class)
            : Preferences.userRoot().node(System.getProperty("panelcontroller.prefs"));
    private final KeySession session = new KeySession();
    private final List<Page> pages = new ArrayList<>();
    private final List<ToggleButton> navButtons = new ArrayList<>();
    private final List<Image> icons = new ArrayList<>();

    private Stage stage;
    private AppContext ctx;
    private VaultUi vaultUi;
    private GuideTab guide;
    private Scene mainScene;
    private Page current;
    private PublishPage publish;
    private ToolsPage tools;
    private String theme;

    private final StackPane pageHost = new StackPane();
    private final Label pageTitle = Ui.label("", "page-title");
    private final Label pageSubtitle = Ui.label("", "page-subtitle");
    private final Label projectName = Ui.label("");
    private final Label publishBadge = Ui.label("", "badge");
    private Node activityView;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> ErrorLog.write("uncaught (" + t.getName() + ")", e));
        T.init(prefs.get("lang", Locale.getDefault().getLanguage().equals("tr") ? "tr" : "en"));
        loadFonts();
        theme = prefs.get("theme", "light");

        Path root = startRoot();
        String vaultOverride = System.getProperty("panelcontroller.vault", "");
        Path vaultFile = vaultOverride.isBlank() ? KeyVault.defaultLocation() : Path.of(vaultOverride);
        KeyVault vault = Protector.forPlatform().map(p -> new KeyVault(vaultFile, p, KeyVault.DEFAULT_ITERATIONS)).orElse(null);

        ctx = new AppContext(primary, getHostServices(), prefs, session, vault);
        ctx.setProject(root);
        ctx.activity = new ActivityFeed(code -> ctx.openGuide(code));
        ctx.navigator = this::show;
        vaultUi = new VaultUi(primary, vault, session, ctx.background, () -> ctx.refreshAll(), msg -> {
            if (!msg.endsWith("…")) {
                ctx.activity.note(msg.startsWith("✗") ? "err" : "ok", "API", msg.replaceFirst("^[✓✗] ", ""));
            }
        });

        loadIcons();
        themeEveryWindow();

        publish = new PublishPage(ctx);
        pages.addAll(List.of(publish, new RestorePage(ctx), new GitPage(ctx)));
        if (EXTRA_PAGES) {
            tools = new ToolsPage(ctx);
            pages.addAll(List.of(new TasksPage(ctx), tools, new ApiPage(ctx, vaultUi)));
        }
        pages.add(new SettingsPage(ctx, vaultUi, EXTRA_PAGES, this::changeProject, this::setTheme, () -> theme, this::showLock));
        pages.add(new AboutPage(ctx));

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("shell");
        Tab work = new Tab(AppInfo.NAME, workspace());
        work.setClosable(false);
        work.setGraphic(Icons.of("panel", 14));
        tabs.getTabs().add(work);
        guide = new GuideTab(tabs);
        guide.setDark(theme.equals("dark"));
        ctx.guideOpener = guide::open;

        mainScene = new Scene(tabs, 1320, 860);
        style(mainScene);
        mainScene.addEventFilter(KeyEvent.KEY_PRESSED, this::shortcut);
        ctx.refreshers.add(this::refreshVisible);

        primary.setTitle(AppInfo.NAME + " " + AppInfo.VERSION);
        primary.setMinWidth(1080);
        primary.setMinHeight(700);
        primary.getIcons().setAll(icons);
        if (EXTRA_PAGES && vaultUi.hasStoredKey()) {
            showLock();
        } else {
            showMain();
        }
        primary.show();

        Timeline auto = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            if (!ctx.busy.get() && stage.getScene() == mainScene) {
                refreshVisible();
            }
        }));
        auto.setCycleCount(Timeline.INDEFINITE);
        auto.play();
    }

    @Override
    public void stop() {
        session.clear();
        if (tools != null) tools.shutdown();
        if (ctx != null) {
            ctx.worker.shutdownNow();
            ctx.background.shutdownNow();
        }
    }

    /** Montserrat (bundled, SIL Open Font License): regular for text, bold for headings. */
    private static void loadFonts() {
        for (String w : new String[] {"Regular", "SemiBold", "Bold"}) {
            try (InputStream in = PanelApp.class.getResourceAsStream("/fonts/Montserrat-" + w + ".ttf")) {
                if (in != null) {
                    Font.loadFont(in, 13);
                }
            } catch (IOException e) {
                ErrorLog.write("font " + w, e);
            }
        }
    }

    private Path startRoot() {
        Path root = Path.of(prefs.get("root", System.getProperty("user.dir")));
        List<String> args = getParameters().getRaw();
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).equals("--root")) {
                root = Path.of(args.get(i + 1));
            }
        }
        return root;
    }

    // ---------------------------------------------------------------- layout

    private Node workspace() {
        BorderPane inner = new BorderPane();
        inner.setTop(header());
        inner.setCenter(pageHost);
        activityView = ctx.activity.view();
        inner.setRight(activityView);
        inner.getStyleClass().add("app-body");

        BorderPane shell = new BorderPane();
        shell.setLeft(sidebar());
        shell.setCenter(inner);
        shell.setBottom(statusBar());
        return shell;
    }

    private Node sidebar() {
        ImageView logo = new ImageView(icons.size() > 3 ? icons.get(3) : null);
        logo.setFitWidth(32);
        logo.setFitHeight(32);
        VBox name = new VBox(0, Ui.label(AppInfo.NAME, "brand-name"), Ui.label("v" + AppInfo.VERSION + " " + AppInfo.STAGE, "brand-version"));
        HBox brand = new HBox(10, logo, name);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(0, 4, 12, 4));

        VBox nav = new VBox(2);
        ToggleGroup group = new ToggleGroup();
        nav.getChildren().add(Ui.label(T.t("nav.group.work").toUpperCase(Locale.ROOT), "nav-group"));
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.id().equals("settings")) {
                nav.getChildren().add(Ui.label(T.t("nav.group.app").toUpperCase(Locale.ROOT), "nav-group"));
            }
            ToggleButton b = new ToggleButton(p.title());
            b.getStyleClass().add("nav-item");
            b.setToggleGroup(group);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setGraphic(Icons.of(p.icon(), 18));
            b.setTooltip(new Tooltip(p.subtitle() + "  (Ctrl+" + (i + 1) + ")"));
            b.setOnAction(e -> show(p.id()));
            navButtons.add(b);
            nav.getChildren().add(b);
        }
        // the badge (files that differ from live) sits on the right of the Publish item
        int publishIndex = nav.getChildren().indexOf(navButtons.get(0));
        StackPane publishItem = new StackPane();
        nav.getChildren().set(publishIndex, publishItem);
        publishItem.getChildren().addAll(navButtons.get(0), publishBadge);
        StackPane.setAlignment(publishBadge, Pos.CENTER_RIGHT);
        StackPane.setMargin(publishBadge, new Insets(0, 10, 0, 0));
        publishBadge.setMouseTransparent(true);
        publishBadge.setVisible(false);

        Button help = Ui.button(T.t("nav.guide"), "guide");
        help.setMaxWidth(Double.MAX_VALUE);
        help.setAlignment(Pos.CENTER_LEFT);
        help.setOnAction(e -> ctx.openGuide(""));
        help.setTooltip(new Tooltip("F1"));
        Button themeButton = Ui.button(T.t(theme.equals("dark") ? "nav.theme.light" : "nav.theme.dark"),
                theme.equals("dark") ? "sun" : "moon", "ghost");
        themeButton.setMaxWidth(Double.MAX_VALUE);
        themeButton.setAlignment(Pos.CENTER_LEFT);
        themeButton.setOnAction(e -> {
            setTheme(theme.equals("dark") ? "light" : "dark");
            themeButton.setText(T.t(theme.equals("dark") ? "nav.theme.light" : "nav.theme.dark"));
            themeButton.setGraphic(Icons.of(theme.equals("dark") ? "sun" : "moon", 16));
        });

        VBox side = new VBox(brand, nav);
        VBox.setVgrow(nav, Priority.ALWAYS);
        side.getChildren().add(new VBox(6, help, themeButton));
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(232);
        side.setMinWidth(232);
        return side;
    }

    private Node header() {
        VBox titles = new VBox(2, pageTitle, pageSubtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);
        HBox chip = new HBox(8, Icons.of("folder", 16), projectName);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("project-chip");
        chip.setOnMouseClicked(e -> show("settings"));
        Tooltip.install(chip, new Tooltip(T.t("header.project.tip")));
        Button refresh = Ui.iconButton("refresh", T.t("header.refresh"));
        refresh.setOnAction(e -> refreshAndCompare());
        ToggleButton activity = new ToggleButton(T.t("header.activity"));
        activity.getStyleClass().add("btn");
        activity.setGraphic(Icons.of("activity", 16));
        activity.setSelected(true);
        activity.setOnAction(e -> {
            activityView.setVisible(activity.isSelected());
            activityView.setManaged(activity.isSelected());
        });
        HBox h = new HBox(10, titles, chip, refresh, activity);
        h.getStyleClass().add("page-header");
        return h;
    }

    private Node statusBar() {
        Label credit = Ui.label(AppInfo.title() + "  ·  " + T.t("status.credit", AppInfo.AUTHOR));
        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(14, 14);
        spin.setMaxSize(14, 14);
        Label busy = new Label();
        busy.textProperty().bind(ctx.busyText);
        HBox right = new HBox(6, spin, busy);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.visibleProperty().bind(ctx.busy);
        HBox bar = new HBox(12, credit, Ui.spacer(), right);
        bar.getStyleClass().add("statusbar");
        return bar;
    }

    // ---------------------------------------------------------------- navigation

    private void show(String id) {
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.id().equals(id)) {
                current = p;
                navButtons.get(i).setSelected(true);
                pageTitle.setText(p.title());
                pageSubtitle.setText(p.subtitle());
                pageHost.getChildren().setAll(p.view());
                refreshVisible();
                return;
            }
        }
    }

    private void refreshVisible() {
        projectName.setText(ctx.settings.root.getFileName() == null ? ctx.settings.root.toString()
                : ctx.settings.root.getFileName().toString());
        long diff = ctx.lastCompare == null ? 0 : ctx.lastCompare.count(Comparer.LiveState.DIFFERENT);
        publishBadge.setText(String.valueOf(diff));
        publishBadge.setVisible(diff > 0);
        if (current != null) {
            try {
                current.refresh();
            } catch (RuntimeException e) {
                ErrorLog.write("refresh " + current.id(), e);
            }
        }
    }

    private void refreshAndCompare() {
        refreshVisible();
        publish.compareQuietly();
    }

    private void shortcut(KeyEvent e) {
        if (e.getCode() == KeyCode.F5) {
            refreshAndCompare();
            e.consume();
        } else if (e.getCode() == KeyCode.F1) {
            ctx.openGuide("");
            e.consume();
        } else if (e.isShortcutDown() && e.getCode().isDigitKey()) {
            int n = e.getCode().getCode() - KeyCode.DIGIT1.getCode();
            if (n >= 0 && n < pages.size()) {
                show(pages.get(n).id());
                e.consume();
            }
        }
    }

    // ---------------------------------------------------------------- project, theme, lock

    private void changeProject(Path root) {
        if (!Files.isDirectory(root.resolve("kaynak"))
                && !Ui.confirm(stage, T.t("project.nosources.header"), T.t("project.nosources.text", root), T.t("common.select"))) {
            return;
        }
        ctx.setProject(root);
        ctx.activity.note("ok", T.t("project.changed"), root.toString());
        refreshVisible();
        publish.compareQuietly();
    }

    private void setTheme(String name) {
        theme = name;
        prefs.put("theme", name);
        for (Window w : Window.getWindows()) {
            if (w.getScene() != null) {
                applyTheme(w.getScene().getRoot());
            }
        }
        applyTheme(mainScene.getRoot());
        guide.setDark(name.equals("dark"));
    }

    private void applyTheme(Parent root) {
        root.getStyleClass().remove("dark");
        if (theme.equals("dark")) {
            root.getStyleClass().add("dark");
        }
    }

    private void style(Scene scene) {
        String css = PanelApp.class.getResource("/panel-app.css").toExternalForm();
        if (!scene.getStylesheets().contains(css)) {
            scene.getStylesheets().add(css);
        }
        applyTheme(scene.getRoot());
    }

    private void showLock() {
        session.clear();
        Scene lock = vaultUi.lockScene(this::showMain);
        style(lock);
        stage.setScene(lock);
    }

    private void showMain() {
        stage.setScene(mainScene);
        show(current == null ? "publish" : current.id());
        publish.compareQuietly();
        if (!prefs.getBoolean("welcomed", false)) {
            Platform.runLater(this::welcome);
        }
    }

    /** First start: what to set up, with the system check. */
    private void welcome() {
        prefs.putBoolean("welcomed", true);
        SystemCheckView check = new SystemCheckView(ctx, () -> ctx.cfg.root());
        VBox body = new VBox(10, Ui.wrapped(T.t("welcome.intro")), Ui.wrapped(T.t("welcome.step1")),
                Ui.wrapped(T.t("welcome.step2")), Ui.wrapped(T.t("welcome.step3")),
                Ui.label(T.t("welcome.system"), "section-title"), check);
        body.setPrefWidth(720);
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle(AppInfo.NAME);
        d.setHeaderText(T.t("welcome.header"));
        d.getDialogPane().setContent(body);
        ButtonType go = new ButtonType(T.t("welcome.go"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(go, Ui.cancel());
        check.check();
        if (d.showAndWait().filter(b -> b == go).isPresent()) {
            show("settings");
        }
    }

    // ---------------------------------------------------------------- icons and dialogs

    private void loadIcons() {
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            var url = PanelApp.class.getResource("/icons/panel-controller-" + size + ".png");
            if (url != null) {
                icons.add(new Image(url.toExternalForm()));
            }
        }
    }

    /** Dialogs opened later get the logo, the stylesheet and the current theme. */
    private void themeEveryWindow() {
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (w instanceof Stage s && s.getIcons().isEmpty()) {
                        s.getIcons().setAll(icons);
                    }
                    if (w.getScene() != null) {
                        style(w.getScene());
                    }
                    w.sceneProperty().addListener((o, a, b) -> {
                        if (b != null) style(b);
                    });
                }
            }
        });
    }
}
