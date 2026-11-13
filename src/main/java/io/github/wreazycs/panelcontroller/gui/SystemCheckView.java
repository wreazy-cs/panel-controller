package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.SystemCheck;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Rows of the system check: tool, what it is for, state, and an Install button that opens winget in a
 * console window (its prompts and licence are shown to the person; nothing is accepted silently).
 */
final class SystemCheckView extends VBox {
    private final AppContext ctx;
    private final Supplier<Path> project;
    private final Label summary = Ui.label(T.t("sys.checking"), "muted");

    SystemCheckView(AppContext ctx, Supplier<Path> project) {
        super(8);
        this.ctx = ctx;
        this.project = project;
        getChildren().add(summary);
    }

    void check() {
        summary.setText(T.t("sys.checking"));
        getChildren().setAll(summary);
        Path root = project.get();
        ctx.background.submit(() -> {
            List<SystemCheck.Item> items = SystemCheck.run(root);
            Platform.runLater(() -> show(items));
        });
    }

    /** The detail text of one row in the chosen language. */
    static String detail(SystemCheck.Item i) {
        if (i.id().equals("scripts")) {
            return switch (i.state()) {
                case OK -> "0".equals(i.info()) ? T.t("sys.d.noScripts") : T.t("sys.d.scriptsOk", i.info());
                case MISSING -> T.t("sys.d.scriptsNoPython", i.info());
                default -> i.info();
            };
        }
        if (i.id().equals("winget") && i.state() == SystemCheck.State.MISSING) {
            return T.t("sys.d.wingetMissing");
        }
        return switch (i.state()) {
            case OK -> i.version() + ("bundled".equals(i.info()) ? " · " + T.t("sys.d.bundled") : "");
            case MISSING -> T.t("sys.d.missing");
            case OLD -> T.t("sys.d.old", i.version(), i.minimum());
            case PROBLEM -> i.info();
        };
    }

    private void show(List<SystemCheck.Item> items) {
        long missing = items.stream().filter(i -> !i.ok()).count();
        summary.setText(missing == 0 ? T.t("sys.allOk") : T.t("sys.someMissing", missing));
        getChildren().setAll(summary);
        for (SystemCheck.Item i : items) {
            String kind = switch (i.state()) {
                case OK -> "ok";
                case MISSING, OLD -> "warn";
                case PROBLEM -> "err";
            };
            String name = i.id().equals("scripts") ? T.t("sys.scripts.name") : i.name();
            VBox text = new VBox(2, Ui.label(name, "strong"), Ui.wrapped(T.t("sys." + i.id() + ".needed"), "small", "muted"));
            text.setPrefWidth(260);
            Label detail = Ui.wrapped(detail(i), "small");
            HBox.setHgrow(detail, Priority.ALWAYS);
            detail.setMaxWidth(Double.MAX_VALUE);
            HBox row = new HBox(12, Ui.chip(T.t("sys.state." + i.state().name()), kind), text, detail);
            row.setAlignment(Pos.CENTER_LEFT);
            if (!i.ok() && i.wingetId() != null) {
                Button install = Ui.button(T.t("sys.install"), "download", "small");
                install.setOnAction(e -> install(i));
                row.getChildren().add(install);
            }
            getChildren().add(row);
        }
        Button again = Ui.button(T.t("sys.recheck"), "refresh", "small");
        again.setOnAction(e -> check());
        getChildren().add(again);
    }

    private void install(SystemCheck.Item i) {
        if (!Ui.confirm(ctx.stage, T.t("sys.install.confirm.header", i.name()),
                T.t("sys.install.confirm.text", i.name(), i.wingetId()), T.t("sys.install.go"))) {
            return;
        }
        try {
            new ProcessBuilder(SystemCheck.installCommand(i.wingetId())).start();
            ctx.activity.note("ok", T.t("sys.install.started", i.name()), T.t("sys.install.startedText"));
        } catch (IOException | IllegalArgumentException ex) {
            ctx.activity.note("err", T.t("sys.install.failed", i.name()), ex.getMessage());
        }
    }
}
