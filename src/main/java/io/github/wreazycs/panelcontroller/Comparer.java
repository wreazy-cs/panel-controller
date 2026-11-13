package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Compares the local output with what is live. "DIFFERENT" means we have not uploaded yet, or the
 * panel / W3 Total Cache still serves an older version (suspect the cache first).
 */
public final class Comparer {
    private static final int MAX_SHOWN = 24;
    /** Unchanged lines kept around each change in the diff view. */
    private static final int CONTEXT = 3;
    private static final long LCS_LIMIT = 25_000_000L;

    private final ProjectConfig cfg;
    private final PrintStream out;

    public Comparer(ProjectConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
    }

    public enum LiveState { SAME, DIFFERENT, LOCAL_MISSING, UNREADABLE }

    /**
     * One line of a diff view. {@code kind} is ' ' (same), '-' (only live), '+' (only local) or '~'
     * (unchanged lines left out; {@code text} is how many). Line numbers are 1-based, 0 when not applicable.
     */
    public record Line(char kind, int live, int local, String text) {}

    /** {@code diff} is the short list printed by the command line, {@code lines} the full view with context. */
    public record FileResult(String name, LiveState state, int liveChars, int localChars,
                             long added, long removed, List<String> diff, List<Line> lines) {}

    public record Report(List<FileResult> files) {
        public long count(LiveState s) {
            return files.stream().filter(f -> f.state() == s).count();
        }

        /** True when every compared file is identical to the live one. */
        public boolean allSame() {
            return count(LiveState.DIFFERENT) == 0 && count(LiveState.UNREADABLE) == 0;
        }

        public Optional<FileResult> get(String name) {
            return files.stream().filter(f -> f.name().equals(name)).findFirst();
        }
    }

    /** @param dir directory of the local files (normally the panel output directory) */
    public Report run(Cdn cdn, Path dir) throws IOException {
        out.println("Karsilastirma: " + cfg.rel(dir) + "  <->  canli CDN");
        out.println("=".repeat(72));
        List<FileResult> results = new ArrayList<>();
        for (Slot slot : cfg.slots()) {
            Path file = dir.resolve(slot.outputName());
            String local = Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
            Optional<String> live = cdn.fetch(slot.outputName());
            if (local == null && (live.isEmpty() || live.get().isEmpty())) {
                continue;
            }
            if (local == null) {
                out.printf(Locale.ROOT, "  %-14s yerelde YOK, canlida %d karakter%n", slot.outputName(), live.get().length());
                results.add(new FileResult(slot.outputName(), LiveState.LOCAL_MISSING, live.get().length(), 0, 0, 0, List.of(), List.of()));
                continue;
            }
            if (live.isEmpty()) {
                out.printf("  %-14s CDN'den OKUNAMADI%n", slot.outputName());
                results.add(new FileResult(slot.outputName(), LiveState.UNREADABLE, 0, local.length(), 0, 0, List.of(), List.of()));
                continue;
            }
            if (local.equals(live.get())) {
                out.printf(Locale.ROOT, "  %-14s AYNI   (%d karakter)%n", slot.outputName(), local.length());
                results.add(new FileResult(slot.outputName(), LiveState.SAME, live.get().length(), local.length(), 0, 0, List.of(), List.of()));
                continue;
            }
            String[] liveLines = live.get().split("\n", -1);
            String[] localLines = local.split("\n", -1);
            List<Line> aligned = align(liveLines, localLines);
            List<String> diff = changes(aligned);
            List<Line> view = hunks(liveLines, localLines, aligned, CONTEXT);
            long added = diff.stream().filter(l -> l.startsWith("+")).count();
            long removed = diff.stream().filter(l -> l.startsWith("-")).count();
            out.printf(Locale.ROOT, "  %-14s FARKLI (canli %d / yerel %d karakter, +%d -%d satir)%n",
                    slot.outputName(), live.get().length(), local.length(), added, removed);
            List<String> shown = new ArrayList<>();
            for (String l : diff.stream().limit(MAX_SHOWN).toList()) {
                String cut = l.length() > 110 ? l.substring(0, 110) : l;
                shown.add(cut);
                out.println("      " + cut);
            }
            if (diff.size() > MAX_SHOWN) {
                out.println("      ... (" + (diff.size() - MAX_SHOWN) + " satir daha)");
            }
            results.add(new FileResult(slot.outputName(), LiveState.DIFFERENT, live.get().length(), local.length(),
                    added, removed, shown, view));
        }
        Report report = new Report(results);
        out.println("-".repeat(72));
        if (report.allSame()) {
            out.println("  Tum dosyalar canli ile ayni.");
        } else {
            if (report.count(LiveState.UNREADABLE) > 0) {
                out.println("  " + report.count(LiveState.UNREADABLE) + " dosya CDN'den okunamadi - internet/erisim sorunu olabilir.");
            }
            out.println("  " + (report.count(LiveState.DIFFERENT) + report.count(LiveState.UNREADABLE))
                    + " dosya farkli. (Yeni yukledinse once onbellek: Ctrl+Shift+R / gizli sekme)");
        }
        return report;
    }

    /** Line diff: "-" lines only in {@code a} (live), "+" lines only in {@code b} (local), in order. */
    static List<String> diff(String[] a, String[] b) {
        return changes(align(a, b));
    }

    private static List<String> changes(List<Line> aligned) {
        List<String> res = new ArrayList<>();
        for (Line l : aligned) {
            if (l.kind() != ' ') {
                res.add(l.kind() + l.text());
            }
        }
        return res;
    }

    private static int commonPrefix(String[] a, String[] b) {
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) {
            pre++;
        }
        return pre;
    }

    private static int commonSuffix(String[] a, String[] b, int pre) {
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre
                && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) {
            suf++;
        }
        return suf;
    }

    /**
     * The lines between the common start and the common end of both files, each marked ' ', '-' or '+'
     * and numbered in the live ({@code a}) and local ({@code b}) file.
     */
    static List<Line> align(String[] a, String[] b) {
        int pre = commonPrefix(a, b);
        int suf = commonSuffix(a, b, pre);
        int n = a.length - pre - suf;
        int m = b.length - pre - suf;
        List<Line> res = new ArrayList<>();
        if ((long) n * m > LCS_LIMIT) { // too big for the table: show plain replacement
            for (int i = 0; i < n; i++) res.add(new Line('-', pre + i + 1, 0, a[pre + i]));
            for (int j = 0; j < m; j++) res.add(new Line('+', 0, pre + j + 1, b[pre + j]));
            return res;
        }
        int[][] l = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                l[i][j] = a[pre + i].equals(b[pre + j]) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[pre + i].equals(b[pre + j])) {
                res.add(new Line(' ', pre + i + 1, pre + j + 1, a[pre + i]));
                i++;
                j++;
            } else if (l[i + 1][j] >= l[i][j + 1]) {
                res.add(new Line('-', pre + i + 1, 0, a[pre + i]));
                i++;
            } else {
                res.add(new Line('+', 0, pre + j + 1, b[pre + j]));
                j++;
            }
        }
        while (i < n) {
            res.add(new Line('-', pre + i + 1, 0, a[pre + i]));
            i++;
        }
        while (j < m) {
            res.add(new Line('+', 0, pre + j + 1, b[pre + j]));
            j++;
        }
        return res;
    }

    /** The changes with {@code context} unchanged lines around them; longer stretches become one '~' line. */
    static List<Line> hunks(String[] a, String[] b, List<Line> aligned, int context) {
        int pre = commonPrefix(a, b);
        int suf = commonSuffix(a, b, pre);
        List<Line> all = new ArrayList<>();
        int from = Math.max(0, pre - context);
        if (from > 0) {
            all.add(new Line('~', 0, 0, String.valueOf(from)));
        }
        for (int i = from; i < pre; i++) {
            all.add(new Line(' ', i + 1, i + 1, a[i]));
        }
        all.addAll(aligned);
        int shown = Math.min(context, suf);
        int liveEnd = a.length - suf;
        int localEnd = b.length - suf;
        for (int k = 0; k < shown; k++) {
            all.add(new Line(' ', liveEnd + k + 1, localEnd + k + 1, a[liveEnd + k]));
        }
        if (suf > shown) {
            all.add(new Line('~', 0, 0, String.valueOf(suf - shown)));
        }
        // inside the list: keep only unchanged lines within `context` of a change
        int size = all.size();
        boolean[] keep = new boolean[size];
        int lastChange = -context - 1;
        for (int i = 0; i < size; i++) {
            char kind = all.get(i).kind();
            if (kind == '~') {
                keep[i] = true; // a stretch already left out: not a change
            } else if (kind == ' ') {
                keep[i] = i - lastChange <= context;
            } else {
                keep[i] = true;
                lastChange = i;
                for (int k = Math.max(0, i - context); k < i; k++) {
                    keep[k] = true;
                }
            }
        }
        List<Line> out = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < size; i++) {
            Line l = all.get(i);
            if (!keep[i]) {
                skipped++;
            } else if (l.kind() == '~') {
                out.add(new Line('~', 0, 0, String.valueOf(skipped + Integer.parseInt(l.text()))));
                skipped = 0;
            } else {
                if (skipped > 0) {
                    out.add(new Line('~', 0, 0, String.valueOf(skipped)));
                    skipped = 0;
                }
                out.add(l);
            }
        }
        if (skipped > 0) {
            out.add(new Line('~', 0, 0, String.valueOf(skipped)));
        }
        return out;
    }
}
