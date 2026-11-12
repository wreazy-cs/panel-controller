package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Downloads the live files before an upload, and finds them again when something must be rolled back. */
public final class Backup {
    private final ProjectConfig cfg;
    private final PrintStream out;

    public Backup(ProjectConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
    }

    /** @return exit code; non-zero when any file could not be downloaded (never upload without a backup). */
    public int run(Cdn cdn) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
        Path target = cfg.backupDir().resolve(stamp);
        Files.createDirectories(target);
        out.println("Canli tema dosyalari indiriliyor -> " + cfg.rel(target) + "/");
        int saved = 0;
        int failed = 0;
        for (Slot slot : cfg.slots()) {
            Optional<String> live = cdn.fetch(slot.outputName());
            if (live.isEmpty()) {
                out.printf("  %-14s ALINAMADI%n", slot.outputName());
                failed++;
                continue;
            }
            if (live.get().isBlank()) {
                out.printf("  %-14s bos slot, atlandi%n", slot.outputName());
                continue;
            }
            Files.writeString(target.resolve(slot.outputName()), live.get(), StandardCharsets.UTF_8);
            out.printf(Locale.ROOT, "  %-14s %d karakter%n", slot.outputName(), live.get().length());
            saved++;
        }
        out.println("-".repeat(50));
        out.println("  " + saved + " dosya yedeklendi: " + cfg.rel(target) + "/");
        if (failed > 0 || saved == 0) {
            out.println("  x " + failed + " dosya indirilemedi - YEDEK EKSIK.");
            out.println("    Internet baglantisini kontrol et; yedek alinmadan yukleme yapma.");
            return 1;
        }
        return 0;
    }

    /** Backup stamps, newest first. */
    public List<String> list() throws IOException {
        if (!Files.isDirectory(cfg.backupDir())) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(cfg.backupDir())) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
        }
    }

    /** Path of a backed-up file; {@code stamp} may be "latest". */
    public Optional<Path> find(String stamp, String fileName) throws IOException {
        List<String> all = list();
        String use = "latest".equals(stamp) ? (all.isEmpty() ? null : all.get(0)) : stamp;
        if (use == null) {
            return Optional.empty();
        }
        Path p = cfg.backupDir().resolve(use).resolve(fileName).normalize();
        if (!p.startsWith(cfg.backupDir().normalize())) {
            return Optional.empty(); // refuse path traversal such as ..\..\x
        }
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }
}
