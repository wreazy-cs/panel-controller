package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Panel page contents ({@code veri/sayfalar/*.html}) are pasted into a page's own content area, not
 * a theme slot. The preview wraps one in a minimal page with a notice strip, for a visual check in
 * the browser. The file pasted into the panel is always the raw one.
 */
public final class PagePreview {
    private static final Pattern TITLE = Pattern.compile("<h1[^>]*>(.*?)</h1>|<title>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private PagePreview() {}

    public static Path dir(Path root) {
        return root.resolve("veri").resolve("sayfalar");
    }

    public static List<Path> pages(Path root) throws IOException {
        Path dir = dir(root);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList();
        }
    }

    /** The preview document for one page content file. */
    public static String wrap(String content, String fileName) {
        String title = fileName;
        Matcher m = TITLE.matcher(content);
        if (m.find()) {
            String t = (m.group(1) != null ? m.group(1) : m.group(2)).replaceAll("<[^>]+>", "").strip();
            if (!t.isEmpty()) {
                title = t;
            }
        }
        return "<!DOCTYPE html>\n<html lang=\"tr\">\n<head>\n<meta charset=\"utf-8\">\n<title>" + escape(title) + "</title>\n"
                + "<style>\n"
                + "  body { margin:0; background:#ffffff; font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif; }\n"
                + "  .pc-onizleme { background:#141D2E; color:#C4CEDE; font-size:12.5px; padding:9px 16px; }\n"
                + "  .pc-onizleme b { color:#fff; font-weight:600; }\n"
                + "  .pc-kabuk { padding:28px 0 0; }\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"pc-onizleme\"><b>Önizleme</b> &nbsp;|&nbsp; " + escape(fileName)
                + " &nbsp;|&nbsp; Panele yapıştırılan dosya her zaman veri/sayfalar/ altındaki ham dosyadır.</div>\n"
                + "<div class=\"pc-kabuk\">\n" + content + "\n</div>\n</body>\n</html>\n";
    }

    /** Writes the preview next to other generated files and returns its path. */
    public static Path build(Path root, Path page) throws IOException {
        Path normalized = page.toAbsolutePath().normalize();
        if (!normalized.startsWith(dir(root).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Sayfa veri/sayfalar/ altında olmalı.");
        }
        String content = Files.readString(normalized, StandardCharsets.UTF_8);
        Path out = root.resolve("veri").resolve("uretilen").resolve("onizleme").resolve(page.getFileName().toString());
        Files.createDirectories(out.getParent());
        Files.writeString(out, wrap(content, page.getFileName().toString()), StandardCharsets.UTF_8);
        return out;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
