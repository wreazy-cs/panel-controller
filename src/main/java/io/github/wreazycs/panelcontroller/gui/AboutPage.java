package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.AppInfo;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** About: who made the app, what it does, release notes, licence. */
final class AboutPage implements Page {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final ScrollPane view;

    AboutPage(AppContext ctx) {
        ImageView logo = new ImageView(new Image(AboutPage.class.getResource("/icons/panel-controller-128.png").toExternalForm()));
        logo.setFitWidth(80);
        logo.setFitHeight(80);
        Hyperlink github = new Hyperlink(AppInfo.GITHUB);
        github.setOnAction(e -> ctx.openUrl(AppInfo.GITHUB));
        Button guide = Ui.button(T.t("about.openGuide"), "guide", "primary");
        guide.setOnAction(e -> ctx.openGuide(""));
        HBox version = new HBox(8, Ui.chip("v" + AppInfo.VERSION, "info"), Ui.chip(AppInfo.STAGE, ""));
        version.setAlignment(Pos.CENTER);
        VBox head = new VBox(8, logo, Ui.label(AppInfo.NAME, "page-title"), version, Ui.label(T.t("about.tagline"), "muted"),
                Ui.label(T.t("about.madeBy", AppInfo.AUTHOR), "section-title"), github, guide);
        head.setAlignment(Pos.CENTER);
        VBox top = Ui.card(head);
        top.setPadding(new Insets(24));

        VBox what = Ui.card(Ui.cardHeader(T.t("about.what"), null),
                Ui.wrapped(T.t("about.purpose.1")), Ui.wrapped(T.t("about.purpose.2")), Ui.wrapped(T.t("about.purpose.3")));

        VBox notes = Ui.card(Ui.cardHeader(T.t("about.notes"), null));
        for (AppInfo.Release r : AppInfo.releases(T.lang())) {
            HBox rh = new HBox(10, Ui.chip("v" + r.version(), r.version().equals(AppInfo.VERSION) ? "info" : ""),
                    Ui.label(r.title(), "strong"), Ui.spacer(), Ui.label(r.date().format(DAY), "muted", "small"));
            rh.setAlignment(Pos.CENTER_LEFT);
            VBox one = new VBox(6, rh);
            section(one, T.t("about.added"), "ok", r.added());
            section(one, T.t("about.changed"), "info", r.changed());
            section(one, T.t("about.fixed"), "warn", r.fixed());
            one.setPadding(new Insets(0, 0, 8, 0));
            notes.getChildren().add(one);
        }

        VBox legal = Ui.card(Ui.cardHeader(T.t("about.legal"), null),
                Ui.wrapped(T.t("about.legal.1")), Ui.wrapped(T.t("about.legal.2")), Ui.wrapped(T.t("about.legal.3"), "muted"));

        VBox content = new VBox(16, top, what, notes, legal);
        content.setPadding(new Insets(4, 24, 24, 24));
        content.setMaxWidth(860);
        VBox center = new VBox(content);
        center.setAlignment(Pos.TOP_CENTER);
        view = new ScrollPane(center);
        view.setFitToWidth(true);
    }

    private static void section(VBox box, String title, String kind, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        box.getChildren().add(Ui.chip(title, kind));
        for (String s : items) {
            HBox h = new HBox(8, Ui.label("•", "muted"), Ui.wrapped(s));
            h.setAlignment(Pos.TOP_LEFT);
            box.getChildren().add(h);
        }
    }

    @Override public String id() { return "about"; }
    @Override public String title() { return T.t("about.title"); }
    @Override public String subtitle() { return T.t("about.subtitle"); }
    @Override public String icon() { return "about"; }
    @Override public Node view() { return view; }
    @Override public void refresh() { }
}
