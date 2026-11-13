package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Name, version, author and release notes (release-notes_tr.txt / release-notes_en.txt).
 *
 * <p>Rule: every change that alters behaviour bumps {@link #VERSION} (semantic versioning, beta
 * {@code 0.MINOR.PATCH}: new feature = MINOR, fix only = PATCH), the version in pom.xml, and adds
 * a release at the top of both release note files, in the same commit.
 */
public final class AppInfo {
    public static final String NAME = "Panel Controller";
    public static final String VERSION = "0.8.0";
    public static final String STAGE = "beta";
    public static final String AUTHOR = "wreazy-cs";
    public static final String GITHUB = "https://github.com/wreazy-cs/panel-controller";

    public record Release(String version, LocalDate date, String title,
                          List<String> added, List<String> changed, List<String> fixed) {}

    /** Release notes in Turkish ("tr", the default) or English ("en"), newest first. */
    public static List<Release> releases(String lang) {
        String file = "/release-notes_" + ("en".equals(lang) ? "en" : "tr") + ".txt";
        String text;
        try (InputStream in = AppInfo.class.getResourceAsStream(file)) {
            text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        return parse(text);
    }

    static List<Release> parse(String text) {
        List<Release> out = new ArrayList<>();
        String[] head = null;
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("## ")) {
                if (head != null) {
                    out.add(release(head, added, changed, fixed));
                }
                head = line.substring(3).split("\\|");
                added = new ArrayList<>();
                changed = new ArrayList<>();
                fixed = new ArrayList<>();
            } else if (head != null && line.length() > 2) {
                String item = line.substring(2).strip();
                switch (line.charAt(0)) {
                    case '+' -> added.add(item);
                    case '*' -> changed.add(item);
                    case '!' -> fixed.add(item);
                    default -> { }
                }
            }
        }
        if (head != null) {
            out.add(release(head, added, changed, fixed));
        }
        return out;
    }

    private static Release release(String[] head, List<String> added, List<String> changed, List<String> fixed) {
        return new Release(head[0].strip(), LocalDate.parse(head[1].strip()), head.length > 2 ? head[2].strip() : "",
                List.copyOf(added), List.copyOf(changed), List.copyOf(fixed));
    }

    private AppInfo() {}

    public static String title() {
        return NAME + " " + VERSION + " " + STAGE;
    }
}
