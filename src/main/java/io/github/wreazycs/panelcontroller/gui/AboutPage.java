package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.AppInfo;
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

/** Hakkında: who made the app, version, licence, components. */
final class AboutPage implements Page {
    private final ScrollPane view;

    AboutPage(AppContext ctx) {
        ImageView logo = new ImageView(new Image(AboutPage.class.getResource("/icons/panel-controller-128.png").toExternalForm()));
        logo.setFitWidth(88);
        logo.setFitHeight(88);

        Hyperlink github = new Hyperlink(AppInfo.GITHUB);
        github.setOnAction(e -> ctx.openUrl(AppInfo.GITHUB));
        Button guide = Ui.button("Kullanım kılavuzunu aç", "guide", "primary");
        guide.setOnAction(e -> ctx.openGuide(""));

        VBox head = new VBox(8, logo, Ui.label(AppInfo.NAME, "page-title"),
                new HBox(8, Ui.chip("v" + AppInfo.VERSION, "info"), Ui.chip(AppInfo.STAGE, "")),
                Ui.label(AppInfo.TAGLINE, "muted"),
                Ui.label(AppInfo.credit(), "section-title"),
                github, guide);
        head.setAlignment(Pos.CENTER);
        ((HBox) head.getChildren().get(2)).setAlignment(Pos.CENTER);
        VBox top = Ui.card(head);
        top.setAlignment(Pos.CENTER);
        top.setPadding(new Insets(28));

        VBox purpose = Ui.card(Ui.cardHeader("Ne işe yarar", null));
        AppInfo.PURPOSE.forEach(p -> purpose.getChildren().add(Ui.wrapped(p)));

        VBox legal = Ui.card(Ui.cardHeader("Lisans ve bileşenler", null),
                Ui.wrapped("Panel Controller, Apache License 2.0 ile yayınlanır."),
                Ui.wrapped("Kullanılan açık kaynak bileşenler: OpenJFX (arayüz, GPL v2 + Classpath Exception), "
                        + "JNA (Windows DPAPI erişimi, Apache 2.0), JUnit 5 (yalnızca testlerde)."),
                Ui.wrapped("\"PlatinMarket\" adı sahibinin markasıdır. Bu proje bağımsızdır ve PlatinMarket ile resmi bir bağı yoktur.",
                        "muted"));

        VBox content = new VBox(16, top, purpose, legal);
        content.setPadding(new Insets(4, 24, 24, 24));
        content.setMaxWidth(820);
        VBox center = new VBox(content);
        center.setAlignment(Pos.TOP_CENTER);
        view = new ScrollPane(center);
        view.setFitToWidth(true);
    }

    @Override public String id() { return "about"; }
    @Override public String title() { return "Hakkında"; }
    @Override public String subtitle() { return AppInfo.credit() + "."; }
    @Override public String icon() { return "about"; }
    @Override public Node view() { return view; }
    @Override public void refresh() { }
}
