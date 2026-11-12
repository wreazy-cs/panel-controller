package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.AppInfo;
import io.github.wreazycs.panelcontroller.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.concurrent.Worker;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.web.WebView;

/**
 * The user guide in its own tab. The page is an HTML file with searchable headings, diagrams and
 * the error code table, which is generated from {@link Problem} so it always matches the app.
 */
final class GuideTab {
    private final TabPane tabs;
    private Tab tab;
    private WebView web;
    private boolean loaded;
    private String pending = "";
    private boolean dark;

    GuideTab(TabPane tabs) {
        this.tabs = tabs;
    }

    /** Opens (or switches to) the guide; {@code anchor} is a section id or an error code, "" for the top. */
    void open(String anchor) {
        String safe = anchor == null ? "" : anchor.replaceAll("[^A-Za-z0-9-]", "");
        if (tab == null || !tabs.getTabs().contains(tab)) {
            create();
        }
        tabs.getSelectionModel().select(tab);
        if (loaded) {
            go(safe);
        } else {
            pending = safe;
        }
    }

    void setDark(boolean value) {
        dark = value;
        if (loaded) {
            web.getEngine().executeScript("setTheme(" + dark + ")");
        }
    }

    private void create() {
        web = new WebView();
        web.setContextMenuEnabled(false);
        loaded = false;
        web.getEngine().getLoadWorker().stateProperty().addListener((o, was, now) -> {
            if (now == Worker.State.SUCCEEDED) {
                loaded = true;
                web.getEngine().executeScript("setTheme(" + dark + ")");
                go(pending);
                pending = "";
            }
        });
        try {
            Path file = ErrorLog.file().getParent().resolve("guide.html");
            Files.createDirectories(file.getParent());
            Files.writeString(file, html(), StandardCharsets.UTF_8);
            web.getEngine().load(file.toUri().toString());
        } catch (IOException e) {
            ErrorLog.write("kılavuz", e);
            web.getEngine().loadContent(html());
        }
        tab = new Tab("Kullanım kılavuzu", web);
        tab.setGraphic(Icons.of("guide", 14));
        tab.setOnClosed(e -> {
            tab = null;
            web = null;
            loaded = false;
        });
        tabs.getTabs().add(tab);
    }

    private void go(String anchor) {
        web.getEngine().executeScript("go('" + anchor + "')");
        if (anchor.isEmpty()) {
            web.requestFocus();
        }
    }

    static String html() {
        String template;
        try (InputStream in = GuideTab.class.getResourceAsStream("/guide/guide.html")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            return "<p>Kılavuz bulunamadı.</p>";
        }
        return template.replace("{{VERSION}}", AppInfo.VERSION)
                .replace("{{AUTHOR}}", AppInfo.AUTHOR)
                .replace("{{ERRORS}}", errorRows());
    }

    /** The error table, grouped by the first digit of the code. */
    static String errorRows() {
        StringBuilder sb = new StringBuilder();
        char group = ' ';
        for (Problem p : Problem.values()) {
            char g = p.code().charAt(3);
            if (g != group) {
                group = g;
                sb.append("<tr class=\"err-group\"><td colspan=\"4\">").append(groupName(g)).append("</td></tr>\n");
            }
            String[] sev = switch (p.severity()) {
                case ERROR -> new String[] {"err", "Hata"};
                case WARNING -> new String[] {"warn", "Uyarı"};
                case INFO -> new String[] {"info", "Bilgi"};
            };
            sb.append("<tr class=\"code-row\" id=\"").append(p.code()).append("\">")
                    .append("<td><code>").append(p.code()).append("</code></td>")
                    .append("<td><span class=\"chip ").append(sev[0]).append("\">").append(sev[1]).append("</span></td>")
                    .append("<td><b>").append(esc(p.title())).append("</b><br>").append(esc(p.meaning())).append("</td>")
                    .append("<td>").append(esc(p.fix())).append("</td></tr>\n");
        }
        return sb.toString();
    }

    private static String groupName(char g) {
        return switch (g) {
            case '1' -> "1xx · Derleme (kaynak/ → cikti/)";
            case '2' -> "2xx · Panel simülasyonu (doğrula)";
            case '3' -> "3xx · Canlı site ve yedek";
            case '4' -> "4xx · Git";
            case '5' -> "5xx · API anahtarı";
            case '6' -> "6xx · Araçlar";
            default -> "9xx · Beklenmeyen";
        };
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
