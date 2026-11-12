package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Session notes in {@code veri/oturumlar/}: a hand-over note per work session, opened with the start
 * time written in (outside git).
 */
public final class SessionNotes {
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9-]{1,32}");

    private SessionNotes() {}

    public static Path dir(Path root) {
        return root.resolve("veri").resolve("oturumlar");
    }

    /** Result: the note's path and whether it was created now (false = it already existed). */
    public record Opened(Path file, boolean created) {}

    /** Creates {@code OTURUM-<date>-<name>.md}; an existing note is never overwritten. */
    public static Opened open(Path root, String name, LocalDateTime now) throws IOException {
        String n = name == null ? "" : name.strip().toLowerCase(java.util.Locale.ROOT);
        if (!SAFE_NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Ad yalnızca küçük harf, rakam ve tire olabilir (örnek: samet).");
        }
        Path dir = dir(root);
        Files.createDirectories(dir);
        Path file = dir.resolve("OTURUM-" + now.format(DateTimeFormatter.ISO_LOCAL_DATE) + "-" + n + ".md");
        if (Files.exists(file)) {
            return new Opened(file, false);
        }
        String day = now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        String body = "# Oturum notu: " + day + " (" + n + ")\n\n"
                + "**Başlangıç:** " + now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH.mm")) + "\n\n"
                + "## Ne yapıldı ve neden\n\n\n"
                + "## Yüklendi mi / son yedek\n\n\n"
                + "## Açık kararlar\n\n\n"
                + "## Geri alma\n\n";
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return new Opened(file, true);
    }

    /** Notes, newest first. */
    public static List<Path> list(Path root) throws IOException {
        Path dir = dir(root);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
        }
    }
}
