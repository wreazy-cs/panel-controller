package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Read-only picture of a project: slot sizes, whether output is up to date, backup age, git state. */
public final class ProjectStatus {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private static final Pattern AHEAD = Pattern.compile("ahead (\\d+)");

    private ProjectStatus() {}

    public enum State {
        /** No sources and no output. */
        EMPTY,
        /** Sources exist but nothing was built yet. */
        NO_OUTPUT,
        /** Sources changed after the output was built (or output has no sources left). */
        STALE,
        /** Output is bigger than the per-slot target. */
        OVER,
        OK
    }

    public record SlotRow(Slot slot, int pieces, int outputChars, int target, State state) {
        public double percent() {
            return outputChars < 0 ? 0 : 100.0 * outputChars / target;
        }
    }

    public static List<SlotRow> slotRows(ProjectConfig cfg) throws IOException {
        List<SlotRow> rows = new ArrayList<>();
        for (Slot slot : cfg.slots()) {
            String ext = slot.isJs() ? ".js" : ".css";
            Path dir = cfg.sourceDir().resolve(slot.folder());
            int pieces = 0;
            FileTime newestSource = FileTime.fromMillis(0);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path p : s.filter(x -> x.getFileName().toString().endsWith(ext)).toList()) {
                        pieces++;
                        FileTime t = Files.getLastModifiedTime(p);
                        if (t.compareTo(newestSource) > 0) {
                            newestSource = t;
                        }
                    }
                }
            }
            Path out = cfg.outputDir().resolve(slot.outputName());
            boolean hasOut = Files.isRegularFile(out);
            int chars = hasOut ? charCount(out) : -1;
            State state;
            if (pieces == 0 && !hasOut) {
                state = State.EMPTY;
            } else if (!hasOut) {
                state = State.NO_OUTPUT;
            } else if (pieces == 0 || Files.getLastModifiedTime(out).compareTo(newestSource) < 0) {
                state = State.STALE;
            } else if (chars > cfg.slotTarget()) {
                state = State.OVER;
            } else {
                state = State.OK;
            }
            rows.add(new SlotRow(slot, pieces, chars, cfg.slotTarget(), state));
        }
        return rows;
    }

    private static int charCount(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).length();
        } catch (java.nio.charset.CharacterCodingException e) {
            return (int) Files.size(file);
        }
    }

    /** Age of the newest backup, empty when there is none. */
    public static Optional<Duration> backupAge(ProjectConfig cfg, Clock clock) throws IOException {
        List<String> stamps = Backup.quiet(cfg).list();
        for (String stamp : stamps) { // newest first; skip folders that are not backups
            try {
                LocalDateTime t = LocalDateTime.parse(stamp, STAMP);
                return Optional.of(Duration.between(t, LocalDateTime.now(clock)));
            } catch (DateTimeParseException e) {
                // not a backup folder
            }
        }
        return Optional.empty();
    }

    public static String describeAge(Duration d) {
        long min = Math.max(0, d.toMinutes());
        if (min < 1) return "az önce";
        if (min < 60) return min + " dk önce";
        if (min < 48 * 60) return (min / 60) + " saat önce";
        return (min / 1440) + " gün önce";
    }

    /** One line such as "main · 3 değişiklik · 1 push edilmemiş"; empty when not a git repository. */
    public static Optional<String> git(Path root) {
        try {
            Process p = new ProcessBuilder("git", "-C", root.toString(), "status", "--porcelain=v1", "-b")
                    .redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            if (p.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(describeGit(text));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static String describeGit(String porcelain) {
        List<String> lines = porcelain.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty() || !lines.get(0).startsWith("## ")) {
            return "git";
        }
        String head = lines.get(0).substring(3);
        String branch = head.split("\\.\\.\\.| ", 2)[0];
        int changes = lines.size() - 1;
        StringBuilder sb = new StringBuilder(branch);
        sb.append(changes == 0 ? " · temiz" : " · " + changes + " değişiklik");
        Matcher m = AHEAD.matcher(head);
        if (m.find()) {
            sb.append(" · ").append(m.group(1)).append(" push edilmemiş");
        }
        return sb.toString();
    }
}
