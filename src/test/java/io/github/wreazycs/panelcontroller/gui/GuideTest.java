package io.github.wreazycs.panelcontroller.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.wreazycs.panelcontroller.AppInfo;
import io.github.wreazycs.panelcontroller.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GuideTest {

    @AfterEach
    void resetLanguage() {
        T.init("tr");
    }

    @Test
    void bothGuidesHaveTheShownCodesAndNoPlaceholders() {
        for (String lang : List.of("tr", "en")) {
            T.init(lang);
            String html = GuideTab.html(lang);
            for (Problem p : Problem.values()) {
                boolean shown = GuideTab.shownGroup(p.code().charAt(3));
                assertEquals(shown, html.contains("id=\"" + p.code() + "\""), lang + " " + p.code());
            }
            assertFalse(html.contains("{{"), lang + ": unfilled placeholder");
            assertTrue(html.contains(AppInfo.VERSION));
            assertTrue(html.contains("@font-face"), lang + ": Montserrat embedded");
            assertTrue(html.contains("window.go"), lang + ": script included");
        }
    }

    @Test
    void everyGuideLinkPointsToSomething() {
        for (String lang : List.of("tr", "en")) {
            T.init(lang);
            String html = GuideTab.html(lang);
            Matcher m = Pattern.compile("data-go=\"([^\"]+)\"").matcher(html);
            while (m.find()) {
                assertTrue(html.contains("id=\"" + m.group(1) + "\""), lang + ": broken link to " + m.group(1));
            }
        }
    }

    private static Properties bundle(String lang) throws IOException {
        Properties p = new Properties();
        try (InputStream in = GuideTest.class.getResourceAsStream("/i18n/messages_" + lang + ".properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void bothLanguagesHaveTheSameKeys() throws IOException {
        Set<String> tr = new TreeSet<>(bundle("tr").stringPropertyNames());
        Set<String> en = new TreeSet<>(bundle("en").stringPropertyNames());
        Set<String> onlyTr = new TreeSet<>(tr);
        onlyTr.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(tr);
        assertTrue(onlyTr.isEmpty(), "only in tr: " + onlyTr);
        assertTrue(onlyEn.isEmpty(), "only in en: " + onlyEn);
    }

    /** Every T.t("key") in the shown pages exists in the language files. */
    @Test
    void everyKeyUsedInTheCodeExists() throws IOException {
        Properties tr = bundle("tr");
        Set<String> missing = new HashSet<>();
        Pattern use = Pattern.compile("T\\.t\\(\"([a-zA-Z0-9._-]+)\"");
        Path dir = Path.of("src/main/java/io/github/wreazycs/panelcontroller/gui");
        List<String> hidden = List.of("TasksPage.java", "ToolsPage.java", "ApiPage.java", "VaultUi.java");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> !hidden.contains(p.getFileName().toString())).toList()) {
                Matcher m = use.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    // a key ending in "." is a prefix completed at run time; those are checked below
                    if (!m.group(1).endsWith(".") && !tr.containsKey(m.group(1))) {
                        missing.add(f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "missing keys: " + missing);
        for (String state : List.of("OK", "MISSING", "OLD", "PROBLEM")) {
            assertTrue(tr.containsKey("sys.state." + state));
        }
        for (String id : List.of("java", "git", "node", "python", "scripts", "winget")) {
            assertTrue(tr.containsKey("sys." + id + ".needed"), id);
        }
    }

    @Test
    void shownErrorCodesAreTranslated() throws IOException {
        Properties en = bundle("en");
        for (Problem p : Problem.values()) {
            if (GuideTab.shownGroup(p.code().charAt(3))) {
                for (String part : List.of("title", "meaning", "fix")) {
                    assertTrue(en.containsKey("pc." + p.code() + "." + part), p.code() + "." + part);
                }
            }
        }
    }
}
