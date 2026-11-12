package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The project's tasks/ folder: one Markdown file per task, the file name carries dates and state:
 * {@code <start>[_to_<end>]_<slug>_<status>.md}.
 */
public final class Tasks {
    private static final Pattern NAME = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})(?:_to_(\\d{4}-\\d{2}-\\d{2}))?_(.+?)_(todo|in-progress|on-hold|done|cancelled)\\.md$");
    private static final Pattern CHECK = Pattern.compile("^\\s*[-*]\\s+\\[([ xX~])\\]");

    private Tasks() {}

    public enum Status {
        IN_PROGRESS("in-progress", "Devam ediyor"),
        TODO("todo", "Başlanmadı"),
        ON_HOLD("on-hold", "Beklemede"),
        DONE("done", "Bitti"),
        CANCELLED("cancelled", "İptal"),
        UNKNOWN("?", "Durum eki yok");

        public final String id;
        public final String label;

        Status(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public boolean active() {
            return this == IN_PROGRESS || this == TODO || this == ON_HOLD || this == UNKNOWN;
        }

        static Status of(String id) {
            for (Status s : values()) {
                if (s.id.equals(id)) return s;
            }
            return UNKNOWN;
        }
    }

    /** One task. {@code title} comes from the file's first heading; checks count the "- [ ]" items. */
    public record Task(Path file, LocalDate start, LocalDate end, String slug, Status status,
                       String title, String priority, int checksDone, int checksTotal) {}

    /** Tasks sorted by state (in progress first) then by date, newest first. */
    public static List<Task> read(Path root) throws IOException {
        Path dir = root.resolve("tasks");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase("README.md")).toList();
        }
        List<Task> out = new ArrayList<>();
        for (Path f : files) {
            out.add(parse(f));
        }
        out.sort(Comparator.comparing(Task::status).thenComparing(Task::start, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    static Task parse(Path f) throws IOException {
        String name = f.getFileName().toString();
        Matcher m = NAME.matcher(name);
        LocalDate start = null;
        LocalDate end = null;
        String slug = name.substring(0, name.length() - 3);
        Status status = Status.UNKNOWN;
        if (m.matches()) {
            start = LocalDate.parse(m.group(1));
            end = m.group(2) == null ? null : LocalDate.parse(m.group(2));
            slug = m.group(3);
            status = Status.of(m.group(4));
        }
        String title = slug.replace('-', ' ');
        String priority = "";
        int done = 0;
        int total = 0;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.startsWith("# ") && title.equals(slug.replace('-', ' '))) {
                title = line.substring(2).strip();
            } else if (line.contains("**Priority:**")) {
                priority = line.substring(line.indexOf("**Priority:**") + 13).strip();
            }
            Matcher c = CHECK.matcher(line);
            if (c.find()) {
                total++;
                if (!c.group(1).equals(" ")) {
                    done += c.group(1).equals("~") ? 0 : 1;
                }
            }
        }
        return new Task(f, start, end, slug, status, title, priority, done, total);
    }
}
