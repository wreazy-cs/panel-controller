package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.AppInfo;
import io.github.wreazycs.panelcontroller.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javafx.concurrent.Worker;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.web.WebView;

/**
 * The user guide in its own tab (guide_tr.html / guide_en.html). The error code table is generated
 * from {@link Problem} and the language files, so it always matches the app.
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
        String html = html(T.lang());
        try {
            Path file = ErrorLog.file().getParent().resolve("guide_" + T.lang() + ".html");
            Files.createDirectories(file.getParent());
            Files.writeString(file, html, StandardCharsets.UTF_8);
            web.getEngine().load(file.toUri().toString());
        } catch (IOException e) {
            ErrorLog.write("guide", e);
            web.getEngine().loadContent(html);
        }
        tab = new Tab(T.t("tab.guide"), web);
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

    /** The guide page in the given language, with fonts, version and error table filled in. */
    static String html(String lang) {
        String file = "/guide/guide_" + ("en".equals(lang) ? "en" : "tr") + ".html";
        String template;
        try (InputStream in = GuideTab.class.getResourceAsStream(file)) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            return "<p>Guide not found.</p>";
        }
        String script;
        try (InputStream in = GuideTab.class.getResourceAsStream("/guide/guide.js")) {
            script = "<script>\n" + new String(in.readAllBytes(), StandardCharsets.UTF_8) + "</script>";
        } catch (IOException | NullPointerException e) {
            script = "";
        }
        return template.replace("{{FONTS}}", fontFaces())
                .replace("{{SCRIPT}}", script)
                .replace("{{VERSION}}", AppInfo.VERSION)
                .replace("{{ERRORS}}", errorRows(lang));
    }

    /** Error groups shown in the guide (API, tools and key codes only when those pages are on). */
    static boolean shownGroup(char g) {
        return PanelApp.EXTRA_PAGES || g == '1' || g == '2' || g == '3' || g == '4' || g == '9';
    }

    static String errorRows(String lang) {
        StringBuilder sb = new StringBuilder();
        boolean en = "en".equals(lang);
        char group = ' ';
        for (Problem p : Problem.values()) {
            char g = p.code().charAt(3);
            if (!shownGroup(g)) {
                continue;
            }
            if (g != group) {
                group = g;
                sb.append("<tr class=\"err-group\"><td colspan=\"4\">").append(groupName(g, en)).append("</td></tr>\n");
            }
            String[] sev = switch (p.severity()) {
                case ERROR -> new String[] {"err", en ? "Error" : "Hata"};
                case WARNING -> new String[] {"warn", en ? "Warning" : "Uyarı"};
                case INFO -> new String[] {"info", en ? "Info" : "Bilgi"};
            };
            sb.append("<tr class=\"code-row\" id=\"").append(p.code()).append("\">")
                    .append("<td><code>").append(p.code()).append("</code></td>")
                    .append("<td><span class=\"chip ").append(sev[0]).append("\">").append(sev[1]).append("</span></td>")
                    .append("<td><b>").append(esc(T.title(p))).append("</b><br>").append(esc(T.meaning(p))).append("</td>")
                    .append("<td>").append(esc(T.fix(p))).append("</td></tr>\n");
        }
        return sb.toString();
    }

    private static String groupName(char g, boolean en) {
        return switch (g) {
            case '1' -> en ? "1xx · Build (kaynak/ → cikti/)" : "1xx · Derleme (kaynak/ → cikti/)";
            case '2' -> en ? "2xx · Panel simulation (validate)" : "2xx · Panel simülasyonu (doğrula)";
            case '3' -> en ? "3xx · Live site and backup" : "3xx · Canlı site ve yedek";
            case '4' -> "4xx · Git";
            case '5' -> en ? "5xx · API key" : "5xx · API anahtarı";
            case '6' -> en ? "6xx · Tools" : "6xx · Araçlar";
            case '7' -> "7xx · API";
            default -> en ? "9xx · Unexpected" : "9xx · Beklenmeyen";
        };
    }

    /** The bundled Montserrat, embedded so the guide looks like the app without any network access. */
    private static String fontFaces() {
        StringBuilder css = new StringBuilder();
        String[][] faces = {{"Regular", "400"}, {"SemiBold", "600"}, {"Bold", "700"}};
        for (String[] f : faces) {
            try (InputStream in = GuideTab.class.getResourceAsStream("/fonts/Montserrat-" + f[0] + ".ttf")) {
                if (in == null) continue;
                css.append("@font-face{font-family:'Montserrat';font-weight:").append(f[1])
                        .append(";src:url(data:font/ttf;base64,").append(Base64.getEncoder().encodeToString(in.readAllBytes()))
                        .append(") format('truetype');}\n");
            } catch (IOException e) {
                ErrorLog.write("guide font", e);
            }
        }
        return css.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
