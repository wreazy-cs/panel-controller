package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.Problem;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Interface texts in Turkish or English (i18n/messages_tr.properties, messages_en.properties).
 * {@code t("key", a, b)} fills {0}, {1}. A missing key shows the key itself, so it is easy to spot.
 */
final class T {
    private static ResourceBundle bundle = load("tr");
    private static String lang = "tr";

    private T() {}

    static void init(String language) {
        lang = "en".equals(language) ? "en" : "tr";
        bundle = load(lang);
    }

    static String lang() {
        return lang;
    }

    static Locale locale() {
        return lang.equals("en") ? Locale.ENGLISH : Locale.forLanguageTag("tr-TR");
    }

    private static ResourceBundle load(String language) {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag(language),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    static String t(String key, Object... args) {
        String s;
        try {
            s = bundle.getString(key);
        } catch (MissingResourceException e) {
            s = key;
        }
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    static boolean has(String key) {
        return bundle.containsKey(key);
    }

    // ------------------------------------------------------- error code texts

    static String title(Problem p) {
        return problem(p, "title", p.title());
    }

    static String meaning(Problem p) {
        return problem(p, "meaning", p.meaning());
    }

    static String fix(Problem p) {
        return problem(p, "fix", p.fix());
    }

    private static String problem(Problem p, String part, String fallback) {
        String key = "pc." + p.code() + "." + part;
        return bundle.containsKey(key) ? bundle.getString(key) : fallback;
    }
}
