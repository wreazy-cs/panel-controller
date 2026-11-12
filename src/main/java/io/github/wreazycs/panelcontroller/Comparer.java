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

    /** @param dir directory of the local files (normally the panel output directory) */
    public int run(Cdn cdn, Path dir) throws IOException {
        out.println("Karsilastirma: " + cfg.rel(dir) + "  <->  canli CDN");
        out.println("=".repeat(72));
        int different = 0;
        int unreadable = 0;
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
                continue;
            }
            if (live.isEmpty()) {
                out.printf("  %-14s CDN'den OKUNAMADI%n", slot.outputName());
                different++;
                unreadable++;
                continue;
            }
            if (local.equals(live.get())) {
                out.printf(Locale.ROOT, "  %-14s AYNI   (%d karakter)%n", slot.outputName(), local.length());
                continue;
            }
            different++;
            List<String> diff = diff(live.get().split("\n", -1), local.split("\n", -1));
            long added = diff.stream().filter(l -> l.startsWith("+")).count();
            long removed = diff.stream().filter(l -> l.startsWith("-")).count();
            out.printf(Locale.ROOT, "  %-14s FARKLI (canli %d / yerel %d karakter, +%d -%d satir)%n",
                    slot.outputName(), live.get().length(), local.length(), added, removed);
            for (String l : diff.stream().limit(MAX_SHOWN).toList()) {
                out.println("      " + (l.length() > 110 ? l.substring(0, 110) : l));
            }
            if (diff.size() > MAX_SHOWN) {
                out.println("      ... (" + (diff.size() - MAX_SHOWN) + " satir daha)");
            }
        }
        out.println("-".repeat(72));
        if (different == 0) {
            out.println("  Tum dosyalar canli ile ayni.");
            return 0;
        }
        if (unreadable > 0) {
            out.println("  " + unreadable + " dosya CDN'den okunamadi - internet/erisim sorunu olabilir.");
        }
        out.println("  " + different + " dosya farkli. (Yeni yukledinse once onbellek: Ctrl+Shift+R / gizli sekme)");
        return 1;
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
