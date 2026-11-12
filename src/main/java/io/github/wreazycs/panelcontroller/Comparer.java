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
    private static final long LCS_LIMIT = 25_000_000L;

    private final ProjectConfig cfg;
    private final PrintStream out;

    public Comparer(ProjectConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
    }

    public enum LiveState { SAME, DIFFERENT, LOCAL_MISSING, UNREADABLE }

    public record FileResult(String name, LiveState state, int liveChars, int localChars,
                             long added, long removed, List<String> diff) {}

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
                results.add(new FileResult(slot.outputName(), LiveState.LOCAL_MISSING, live.get().length(), 0, 0, 0, List.of()));
                continue;
            }
            if (live.isEmpty()) {
                out.printf("  %-14s CDN'den OKUNAMADI%n", slot.outputName());
                results.add(new FileResult(slot.outputName(), LiveState.UNREADABLE, 0, local.length(), 0, 0, List.of()));
                continue;
            }
            if (local.equals(live.get())) {
                out.printf(Locale.ROOT, "  %-14s AYNI   (%d karakter)%n", slot.outputName(), local.length());
                results.add(new FileResult(slot.outputName(), LiveState.SAME, live.get().length(), local.length(), 0, 0, List.of()));
                continue;
            }
            List<String> diff = diff(live.get().split("\n", -1), local.split("\n", -1));
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
                    added, removed, shown));
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
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) {
            pre++;
        }
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre
                && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) {
            suf++;
        }
        int n = a.length - pre - suf;
        int m = b.length - pre - suf;
        List<String> res = new ArrayList<>();
        if ((long) n * m > LCS_LIMIT) { // too big for the table: show plain replacement
            for (int i = 0; i < n; i++) res.add("-" + a[pre + i]);
            for (int j = 0; j < m; j++) res.add("+" + b[pre + j]);
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
                i++;
                j++;
            } else if (l[i + 1][j] >= l[i][j + 1]) {
                res.add("-" + a[pre + i++]);
            } else {
                res.add("+" + b[pre + j++]);
            }
        }
        while (i < n) res.add("-" + a[pre + i++]);
        while (j < m) res.add("+" + b[pre + j++]);
        return res;
    }
}
