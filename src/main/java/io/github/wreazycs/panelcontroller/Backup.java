package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Downloads the live files before an upload, and finds them again when something must be rolled back. */
public final class Backup {
    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    private final ProjectConfig cfg;
    private final PrintStream out;

    public Backup(ProjectConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
    }

    /** For read-only use (list, find) where nothing should be printed. */
    public static Backup quiet(ProjectConfig cfg) {
        return new Backup(cfg, new PrintStream(OutputStream.nullOutputStream()));
    }

    public enum FileState { SAVED, EMPTY_SLOT, FAILED }

    public record FileResult(String name, FileState state, int chars) {}

    public record Report(String stamp, Path dir, List<FileResult> files) {
        public long saved() {
            return files.stream().filter(f -> f.state() == FileState.SAVED).count();
        }

        public long failed() {
            return files.stream().filter(f -> f.state() == FileState.FAILED).count();
        }

        /** A backup is only good when every file came down and at least one had content. */
        public boolean ok() {
            return failed() == 0 && saved() > 0;
        }

        public Finding failure() {
            if (ok()) {
                return null;
            }
            List<String> names = files.stream().filter(f -> f.state() == FileState.FAILED).map(FileResult::name).toList();
            return Finding.of(Problem.BACKUP_INCOMPLETE, "", names.isEmpty()
                    ? "hicbir dosya indirilemedi" : failed() + " dosya indirilemedi: " + String.join(", ", names));
        }
    }

    public Report run(Cdn cdn) throws IOException {
        String stamp = LocalDateTime.now().format(STAMP);
        Path target = cfg.backupDir().resolve(stamp);
        Files.createDirectories(target);
        out.println("Canli tema dosyalari indiriliyor -> " + cfg.rel(target) + "/");
        List<FileResult> results = new ArrayList<>();
        for (Slot slot : cfg.slots()) {
            Optional<String> live = cdn.fetch(slot.outputName());
            if (live.isEmpty()) {
                out.printf("  %-14s ALINAMADI%n", slot.outputName());
                results.add(new FileResult(slot.outputName(), FileState.FAILED, 0));
                continue;
            }
            if (live.get().isBlank()) {
                out.printf("  %-14s bos slot, atlandi%n", slot.outputName());
                results.add(new FileResult(slot.outputName(), FileState.EMPTY_SLOT, 0));
                continue;
            }
            Files.writeString(target.resolve(slot.outputName()), live.get(), StandardCharsets.UTF_8);
            out.printf(Locale.ROOT, "  %-14s %d karakter%n", slot.outputName(), live.get().length());
            results.add(new FileResult(slot.outputName(), FileState.SAVED, live.get().length()));
        }
        Report report = new Report(stamp, target, results);
        out.println("-".repeat(50));
        out.println("  " + report.saved() + " dosya yedeklendi: " + cfg.rel(target) + "/");
        if (!report.ok()) {
            out.println("  x " + report.failed() + " dosya indirilemedi - YEDEK EKSIK.");
            out.println("    Internet baglantisini kontrol et; yedek alinmadan yukleme yapma.");
        }
        return report;
    }

    /** Backup stamps, newest first. */
    public List<String> list() throws IOException {
        if (!Files.isDirectory(cfg.backupDir())) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(cfg.backupDir())) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }

    /** Files inside one backup, sorted by name. */
    public List<String> files(String stamp) throws IOException {
        Path dir = cfg.backupDir().resolve(stamp).normalize();
        if (!dir.startsWith(cfg.backupDir().normalize()) || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Path of a backed-up file; {@code stamp} may be "latest" (the newest backup that has this file). */
    public Optional<Path> find(String stamp, String fileName) throws IOException {
        List<String> stamps = "latest".equals(stamp) ? list() : List.of(stamp);
        for (String use : stamps) {
            Path p = cfg.backupDir().resolve(use).resolve(fileName).normalize();
            if (!p.startsWith(cfg.backupDir().normalize())) {
                return Optional.empty(); // refuse path traversal such as ..\..\x
            }
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
