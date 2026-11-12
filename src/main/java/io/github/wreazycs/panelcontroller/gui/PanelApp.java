package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.AppInfo;
import io.github.wreazycs.panelcontroller.Comparer;
import io.github.wreazycs.panelcontroller.vault.KeySession;
import io.github.wreazycs.panelcontroller.vault.KeyVault;
import io.github.wreazycs.panelcontroller.vault.Protector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** The desktop window: side menu, pages, activity panel, guide tab, lock screen. */
public final class PanelApp extends Application {

    /** -Dpanelcontroller.prefs=<name> keeps settings in a separate node (used by tests, so real settings stay untouched). */
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
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> ErrorLog.write("yakalanmamış hata (" + t.getName() + ")", e));
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
            if (!msg.endsWith("…")) { // "Kaydediliyor…" style progress lines do not get a card
                ctx.activity.note(msg.startsWith("✗") ? "err" : "ok", "API anahtarı", msg.replaceFirst("^[✓✗] ", ""));
            }
        });

        loadIcons();
        themeEveryWindow();

        publish = new PublishPage(ctx);
        tools = new ToolsPage(ctx);
        pages.addAll(List.of(publish, new RestorePage(ctx), new GitPage(ctx), new TasksPage(ctx), tools,
                new ApiPage(ctx, vaultUi), new NotesPage(), new AboutPage(ctx),
                new SettingsPage(ctx, vaultUi, this::changeProject, this::setTheme, () -> theme, this::showLock)));

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
        if (vaultUi.hasStoredKey()) {
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
        ImageView logo = new ImageView(icons.isEmpty() ? null : icons.get(3));
        logo.setFitWidth(32);
        logo.setFitHeight(32);
        VBox name = new VBox(0, Ui.label(AppInfo.NAME, "brand-name"), Ui.label("v" + AppInfo.VERSION + " " + AppInfo.STAGE, "brand-version"));
        HBox brand = new HBox(10, logo, name);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(0, 4, 12, 4));

        VBox nav = new VBox(2);
        ToggleGroup group = new ToggleGroup();
        String[] groups = {"Çalışma", null, null, null, null, null, "Uygulama", null, null};
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (groups[i] != null) {
                nav.getChildren().add(Ui.label(groups[i].toUpperCase(java.util.Locale.ROOT), "nav-group"));
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
        // the badge sits on the right of the "Yayın" item (find its place before re-parenting the button)
        int publishIndex = nav.getChildren().indexOf(navButtons.get(0));
        StackPane publishItem = new StackPane();
        nav.getChildren().set(publishIndex, publishItem);
        publishItem.getChildren().addAll(navButtons.get(0), publishBadge);
        StackPane.setAlignment(publishBadge, Pos.CENTER_RIGHT);
        StackPane.setMargin(publishBadge, new Insets(0, 10, 0, 0));
        publishBadge.setMouseTransparent(true);
        publishBadge.setVisible(false);

        Button help = Ui.button("Kullanım kılavuzu", "guide");
        help.setMaxWidth(Double.MAX_VALUE);
        help.setAlignment(Pos.CENTER_LEFT);
        help.setOnAction(e -> ctx.openGuide(""));
        help.setTooltip(new Tooltip("F1"));
        Button themeButton = Ui.button(theme.equals("dark") ? "Açık tema" : "Koyu tema", theme.equals("dark") ? "sun" : "moon", "ghost");
        themeButton.setMaxWidth(Double.MAX_VALUE);
        themeButton.setAlignment(Pos.CENTER_LEFT);
        themeButton.setOnAction(e -> {
            setTheme(theme.equals("dark") ? "light" : "dark");
            themeButton.setText(theme.equals("dark") ? "Açık tema" : "Koyu tema");
            themeButton.setGraphic(Icons.of(theme.equals("dark") ? "sun" : "moon", 16));
        });

        VBox bottom = new VBox(6, help, themeButton);
        VBox side = new VBox(brand, nav);
        VBox.setVgrow(nav, Priority.ALWAYS);
        side.getChildren().add(bottom);
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
        Tooltip.install(chip, new Tooltip("Proje klasörü (değiştirmek için tıkla)"));
        Button refresh = Ui.iconButton("refresh", "Yenile (F5)");
        refresh.setOnAction(e -> refreshAndCompare());
        ToggleButton activity = new ToggleButton("İşlemler");
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
        Label credit = Ui.label(AppInfo.title() + "  ·  " + AppInfo.credit());
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
                ErrorLog.write("sayfa yenileme: " + current.id(), e);
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
                && !Ui.confirm(stage, "Bu klasörde kaynak/ yok",
                root + "\n\nSeçilen klasör bir Panel Controller projesine benzemiyor. Yine de seçilsin mi?", "Seç")) {
            return;
        }
        ctx.setProject(root);
        ctx.activity.note("ok", "Proje değişti", root.toString());
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
        if (stage.getScene() != null) {
            applyTheme(stage.getScene().getRoot());
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

    /** First start: what to set up, with the system check, then straight to the settings page. */
    private void welcome() {
        prefs.putBoolean("welcomed", true);
        SystemCheckView check = new SystemCheckView(ctx, () -> ctx.cfg.root());
        VBox body = new VBox(12,
                Ui.wrapped("Başlamak için üç adım:"),
                Ui.wrapped("1.  Ayarlar → Proje: içinde kaynak/ olan proje klasörünü seç."),
                Ui.wrapped("2.  Ayarlar → Mağaza CDN adresi: canlı tema dosyalarının adresini gir ve Kaydet."),
                Ui.wrapped("3.  Aşağıda eksik görünen araç varsa Kur'a bas (isteğe bağlı)."),
                Ui.label("Sistem kontrolü", "section-title"), check);
        body.setPrefWidth(720);
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> d = new javafx.scene.control.Dialog<>();
        d.initOwner(stage);
        d.setTitle(AppInfo.NAME);
        d.setHeaderText(AppInfo.NAME + "'a hoş geldin");
        d.getDialogPane().setContent(body);
        javafx.scene.control.ButtonType go = new javafx.scene.control.ButtonType("Ayarlara git",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(go, Ui.CANCEL);
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
