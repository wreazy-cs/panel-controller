package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Cdn;
import io.github.wreazycs.panelcontroller.Comparer;
import io.github.wreazycs.panelcontroller.Finding;
import io.github.wreazycs.panelcontroller.Problem;
import io.github.wreazycs.panelcontroller.ProjectConfig;
import io.github.wreazycs.panelcontroller.vault.KeySession;
import io.github.wreazycs.panelcontroller.vault.KeyVault;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.stage.Stage;

/** State and services shared by all pages. */
final class AppContext {

    @FunctionalInterface
    interface Work<R> {
        R run(PrintStream out) throws Exception;
    }

    @FunctionalInterface
    interface Done<R> {
        void accept(ActivityFeed.Entry entry, R result);
    }

    final Stage stage;
    final HostServices host;
    final Preferences prefs;
    final KeySession session;
    final KeyVault vault; // null where there is no OS protector
    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "job"));
    final ExecutorService background = Executors.newCachedThreadPool(r -> daemon(r, "status"));
    final SimpleBooleanProperty busy = new SimpleBooleanProperty(false);
    final SimpleStringProperty busyText = new SimpleStringProperty("");
    final List<Runnable> refreshers = new ArrayList<>();

    ActivityFeed activity;
    Consumer<String> guideOpener = a -> { };
    Consumer<String> navigator = p -> { };

    ProjectSettings settings;
    ProjectConfig cfg;

    /** Last live comparison (null until one ran) and when. */
    Comparer.Report lastCompare;
    LocalDateTime lastCompareTime;
    boolean compareRunning;
    /** Set when the last build/prepare failed, cleared on success. */
    Finding lastBuildFailure;

    AppContext(Stage stage, HostServices host, Preferences prefs, KeySession session, KeyVault vault) {
        this.stage = stage;
        this.host = host;
        this.prefs = prefs;
        this.session = session;
        this.vault = vault;
    }

    void setProject(Path root) {
        settings = ProjectSettings.load(prefs, root);
        cfg = settings.toConfig();
        prefs.put("root", settings.root.toString());
        lastCompare = null;
        lastCompareTime = null;
        lastBuildFailure = null;
    }

    void settingsChanged() {
        settings.save();
        cfg = settings.toConfig();
        lastCompare = null;
    }

    Optional<Cdn> cdn() {
        return cfg.cdnTemplate() == null ? Optional.empty() : Optional.of(new Cdn(cfg.cdnTemplate()));
    }

    /** The project folder has kaynak/ (required). */
    boolean hasSources() {
        return Files.isDirectory(cfg.sourceDir());
    }

    /** A CDN address in the right form is saved (required). */
    boolean hasCdn() {
        return validCdn(cfg.cdnTemplate());
    }

    /** Both required settings are in place; until then Publish, Restore and Git show the setup screen. */
    boolean ready() {
        return hasSources() && hasCdn();
    }

    static boolean validCdn(String address) {
        return address != null && address.strip().startsWith("https://") && address.contains("%s");
    }

    /** Reads the first panel file from {@code address} in the background; the result arrives on the UI thread. */
    void testCdn(String address, Consumer<Optional<String>> result) {
        String file = cfg.slots().get(0).outputName();
        background.submit(() -> {
            Optional<String> r;
            try {
                r = new Cdn(address.strip()).fetch(file);
            } catch (RuntimeException e) {
                r = Optional.empty();
            }
            Optional<String> done = r;
            Platform.runLater(() -> result.accept(done));
        });
    }

    String firstFile() {
        return cfg.slots().get(0).outputName();
    }

    /** True when a CDN address is set; otherwise explains it on a card and offers the settings page. */
    boolean requireCdn() {
        if (cfg.cdnTemplate() != null) {
            return true;
        }
        activity.start(T.t("cdn.missing.card")).done("err", T.t("cdn.missing.summary"),
                List.of(Finding.of(Problem.CDN_NOT_SET, "", "")));
        if (Ui.confirm(stage, T.t("cdn.missing.header"), T.t("cdn.missing.text"), T.t("publish.goSettings"))) {
            navigator.accept("settings");
        }
        return false;
    }

    /** Runs {@code work} off the UI thread, with a card in the activity panel; one job at a time. */
    <R> void run(String title, Work<R> work, Done<R> done) {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        busyText.set(title + "…");
        ActivityFeed.Entry entry = activity.start(title);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        worker.submit(() -> {
            try {
                R result = work.run(out);
                out.flush();
                Platform.runLater(() -> {
                    busy.set(false);
                    busyText.set("");
                    entry.setRaw(buf.toString(StandardCharsets.UTF_8));
                    try {
                        done.accept(entry, result);
                    } catch (RuntimeException ex) {
                        ErrorLog.write(title + " (sonuç)", ex);
                        entry.done("err", T.t("job.resultError"), List.of(Finding.of(Problem.UNEXPECTED, "", ex.toString())));
                    }
                    refreshAll();
                });
            } catch (Exception | LinkageError ex) {
                Finding f = toFinding(ex);
                if (f.problem() == Problem.UNEXPECTED) {
                    ErrorLog.write(title, ex);
                }
                Platform.runLater(() -> {
                    busy.set(false);
                    busyText.set("");
                    entry.setRaw(buf.toString(StandardCharsets.UTF_8));
                    entry.done("err", T.t("job.failed"), List.of(f));
                    refreshAll();
                });
            }
        });
    }

    /** Known failures carry their own code; anything else is PC-901 and goes to the error log. */
    static Finding toFinding(Throwable ex) {
        if (ex instanceof io.github.wreazycs.panelcontroller.BuildException b) {
            return b.finding();
        }
        if (ex instanceof java.net.BindException) {
            return Finding.of(Problem.PORT_IN_USE, "", ex.getMessage());
        }
        if (ex instanceof java.net.ConnectException || ex instanceof java.net.UnknownHostException) {
            return Finding.of(Problem.CDN_UNREADABLE, "", ex.getMessage());
        }
        return Finding.of(Problem.UNEXPECTED, "", String.valueOf(ex.getMessage()));
    }

    /** Disables a control while a job runs. */
    <N extends Node> N guard(N node) {
        node.disableProperty().bind(busy);
        return node;
    }

    void refreshAll() {
        for (Runnable r : refreshers) {
            try {
                r.run();
            } catch (RuntimeException e) {
                ErrorLog.write("yenileme", e);
            }
        }
    }

    void openGuide(String anchor) {
        guideOpener.accept(anchor);
    }

    void open(Path file) {
        host.showDocument(file.toUri().toString());
    }

    void openUrl(String url) {
        host.showDocument(url);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
