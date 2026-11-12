package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The upload workflow from the Python "Kumanda": prepare = backup, build, validate, stopping at the
 * first failure; then paste the changed files in the right order.
 */
public final class Workflow {
    private Workflow() {}

    public enum Step { BACKUP, BUILD, VALIDATE }

    /** Result of "prepare". Steps after the failing one are null (not run). */
    public record PrepareReport(Backup.Report backup, Builder.Report build, PanelValidator.Report validation,
                                Step failedAt, Finding failure) {
        public boolean ok() {
            return failedAt == null;
        }
    }

    /** Backup, build, validate. Stops at the first step that fails. */
    public static PrepareReport prepare(ProjectConfig cfg, PrintStream out) throws IOException {
        out.println(">>> 1/3 CANLI HALI YEDEKLE");
        if (cfg.cdnTemplate() == null) {
            Finding f = Finding.of(Problem.CDN_NOT_SET, "", "");
            out.println("!!! " + f.line());
            return new PrepareReport(null, null, null, Step.BACKUP, f);
        }
        Backup.Report backup = new Backup(cfg, out).run(new Cdn(cfg.cdnTemplate()));
        if (!backup.ok()) {
            out.println("!!! Yedek alinamadi. Devam edilmedi.");
            return new PrepareReport(backup, null, null, Step.BACKUP, backup.failure());
        }
        out.println(">>> 2/3 DERLE");
        Builder.Report build = new Builder(cfg, out).build(Builder.Mode.PANEL, List.of());
        if (!build.ok()) {
            out.println("!!! Derleme hatasi. Panele yukleme.");
            return new PrepareReport(backup, build, null, Step.BUILD, build.failure());
        }
        out.println(">>> 3/3 PANEL SIMULASYONU");
        PanelValidator.Report validation = new PanelValidator(cfg, out).run();
        if (!validation.ok()) {
            out.println("!!! Dogrulama GECMEDI. Panele yukleme.");
            List<Finding> errors = validation.allErrors();
            return new PrepareReport(backup, build, validation, Step.VALIDATE, errors.isEmpty() ? null : errors.get(0));
        }
        out.println();
        out.println("HAZIR. Yapistirma sirasi: once sayfa slotlari, en son genel.");
        int no = 1;
        for (Slot s : pasteOrder(cfg)) {
            out.printf("  %d) %-14s -> panel slotu: %s%n", no++, s.outputName(), s.panelName());
        }
        return new PrepareReport(backup, build, validation, null, null);
    }

    /**
     * Slots that have an output file, in the order to paste them: page slots first, the general
     * slot last. The page slot's code loads right after the general one, so during the switch-over
     * old and new code run side by side; the general slot is the riskiest and goes last.
     */
    public static List<Slot> pasteOrder(ProjectConfig cfg) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : cfg.slots()) {
            if (Files.isRegularFile(cfg.outputDir().resolve(s.outputName()))) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing((Slot s) -> isGeneral(s)).thenComparing(Slot::outputName));
        return out;
    }

    /** The general slot loads on every page. */
    public static boolean isGeneral(Slot s) {
        return s.outputName().startsWith("genel.");
    }

    /** Output file of a slot. */
    public static Path output(ProjectConfig cfg, Slot s) {
        return cfg.outputDir().resolve(s.outputName());
    }
}
