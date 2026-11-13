package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Command line entry point. There is deliberately no command that touches the API key. */
public final class Main {
    private Main() {}

    static final String HELP = """
            Panel Controller - safe CSS/JS workflow for PlatinMarket stores

            Usage: panel-controller [--root <dir>] [--config <file>] <command> [args]

              prepare              Backup + build + validate; stops at the first failure. Run before uploading.
              build [--readable|--commented] [slot...]
                                   Build source pieces into the panel files (default: output dir).
              validate             Simulate the panel on the output files.
              check                build + validate.
              backup               Download the live files into the backup dir.
              compare [--dir <d>]  Compare local output with the live CDN files.
              order                Files to paste, in order (page slots first, general last).
              status               Slot sizes, whether output is current, backup age, git state.
              backups              List backups, newest first.
              copy <file>          Copy an output file (e.g. genel.css) to the clipboard.
              restore <stamp|latest> <file>
                                   Copy a backed-up file to the clipboard, to paste back into the panel.
              tasks [all]          Tasks in tasks/ (done/cancelled hidden unless "all").
              note <name>          Open a session note in veri/oturumlar/ with the start time.
              version              Version and latest release notes.
              help                 This text.

            --root    project directory (default: current directory)
            --config  properties file (cdn URL, slots, directories); see panel-controller.properties.example
            Error codes (PC-xxx) are explained in the user guide inside the desktop app.
            """;

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args, out);
        } catch (IllegalArgumentException | IOException e) {
            out.println("HATA: " + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    static int run(String[] args, PrintStream out) throws IOException {
        Path root = Path.of(".");
        Path configFile = null;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(value(args, ++i, "--root"));
                case "--config" -> configFile = Path.of(value(args, ++i, "--config"));
                default -> rest.add(args[i]);
            }
        }
        if (rest.isEmpty() || rest.get(0).equals("help") || rest.get(0).equals("--help")) {
            out.print(HELP);
            return rest.isEmpty() ? 2 : 0;
        }
        String cmd = rest.get(0);
        List<String> a = rest.subList(1, rest.size());
        if (cmd.equals("version")) {
            printVersion(out, a.contains("all") ? Integer.MAX_VALUE : 3);
            return 0;
        }
        ProjectConfig cfg = ProjectConfig.load(root, configFile);

        switch (cmd) {
            case "prepare":
                return Workflow.prepare(cfg, out).ok() ? 0 : 1;
            case "build": {
                Builder.Mode mode = Builder.Mode.PANEL;
                List<String> filters = new ArrayList<>();
                for (String s : a) {
                    switch (s) {
                        case "--readable" -> mode = Builder.Mode.READABLE;
                        case "--commented" -> mode = Builder.Mode.COMMENTED;
                        default -> filters.add(s);
                    }
                }
                return new Builder(cfg, out).build(mode, filters).ok() ? 0 : 1;
            }
            case "validate":
                return new PanelValidator(cfg, out).run().ok() ? 0 : 1;
            case "check": {
                if (!new Builder(cfg, out).build(Builder.Mode.PANEL, List.of()).ok()) {
                    return 1;
                }
                out.println();
                return new PanelValidator(cfg, out).run().ok() ? 0 : 1;
            }
            case "backup":
                return new Backup(cfg, out).run(cdn(cfg)).ok() ? 0 : 1;
            case "compare": {
                Path dir = cfg.outputDir();
                if (a.size() >= 2 && a.get(0).equals("--dir")) {
                    dir = root.resolve(a.get(1));
                }
                return new Comparer(cfg, out).run(cdn(cfg), dir).allSame() ? 0 : 1;
            }
            case "order": {
                int no = 1;
                for (Slot s : Workflow.pasteOrder(cfg)) {
                    out.printf("%d) %-14s -> %s%n", no++, s.outputName(), s.panelName());
                }
                return 0;
            }
            case "status": {
                for (ProjectStatus.SlotRow r : ProjectStatus.slotRows(cfg)) {
                    if (r.state() == ProjectStatus.State.EMPTY) {
                        continue;
                    }
                    out.printf(Locale.ROOT, "  %-14s %2d parca  %7d krk  %%%5.1f  %s%n",
                            r.slot().outputName(), r.pieces(), Math.max(r.outputChars(), 0), r.percent(), r.state());
                }
                out.println("  yedek: " + ProjectStatus.backupAge(cfg, Clock.systemDefaultZone())
                        .map(ProjectStatus::describeAge).orElse("yok"));
                out.println("  git  : " + ProjectStatus.git(cfg.root()).orElse("depo degil"));
                return 0;
            }
            case "backups": {
                Backup b = Backup.quiet(cfg);
                List<String> all = b.list();
                if (all.isEmpty()) {
                    out.println("Yedek yok: " + cfg.rel(cfg.backupDir()));
                }
                for (String s : all) {
                    out.println(s + "   " + String.join(" ", b.files(s)));
                }
                return 0;
            }
            case "copy": {
                if (a.size() != 1) {
                    throw new IllegalArgumentException("kullanim: copy <dosya>");
                }
                Path file = cfg.outputDir().resolve(a.get(0)).normalize();
                requireInside(cfg.outputDir(), file);
                int n = Clipboard.copyFile(file);
                out.println(a.get(0) + " panoya kopyalandi (" + n + " karakter). Panele yapistirabilirsin.");
                return 0;
            }
            case "restore": {
                if (a.size() != 2) {
                    throw new IllegalArgumentException("kullanim: restore <damga|latest> <dosya>");
                }
                Optional<Path> file = Backup.quiet(cfg).find(a.get(0), a.get(1));
                if (file.isEmpty()) {
                    out.println("Yedek bulunamadi: " + a.get(0) + " / " + a.get(1) + "   ('backups' ile listele)");
                    return 1;
                }
                int n = Clipboard.copyFile(file.get());
                out.println(cfg.rel(file.get()) + " panoya kopyalandi (" + n + " karakter).");
                out.println("Panelde ilgili slota yapistirip kaydet; sonra 'compare' ile dogrula.");
                return 0;
            }
            case "tasks": {
                boolean all = a.contains("all");
                int hidden = 0;
                for (Tasks.Task t : Tasks.read(cfg.root())) {
                    if (!all && !t.status().active()) {
                        hidden++;
                        continue;
                    }
                    out.printf("%-12s %s  %s%n", t.status().id, t.start() == null ? "" : t.start(), t.title());
                }
                if (hidden > 0) {
                    out.println("(" + hidden + " bitmis/iptal gorev gizli; hepsi icin: tasks all)");
                }
                return 0;
            }
            case "note": {
                if (a.size() != 1) {
                    throw new IllegalArgumentException("kullanim: note <ad>   (ornek: note claude)");
                }
                SessionNotes.Opened o = SessionNotes.open(cfg.root(), a.get(0), LocalDateTime.now());
                out.println((o.created() ? "Olusturuldu: " : "Zaten var, dokunulmadi: ") + cfg.rel(o.file()));
                return 0;
            }
            default:
                out.println("Bilinmeyen komut: " + cmd);
                out.print(HELP);
                return 2;
        }
    }

    private static Cdn cdn(ProjectConfig cfg) {
        if (cfg.cdnTemplate() == null) {
            throw new IllegalArgumentException(Problem.CDN_NOT_SET.code() + " " + Problem.CDN_NOT_SET.title()
                    + " (--config dosyasinda cdn=...)");
        }
        return new Cdn(cfg.cdnTemplate());
    }

    static void printVersion(PrintStream out, int count) {
        out.println(AppInfo.title() + "  -  " + AppInfo.credit());
        for (AppInfo.Release r : AppInfo.releases("tr").stream().limit(count).toList()) {
            out.println();
            out.println("v" + r.version() + "  " + r.date() + "  " + r.title());
            r.added().forEach(s -> out.println("  [Eklendi] " + s));
            r.changed().forEach(s -> out.println("  [Degisti] " + s));
            r.fixed().forEach(s -> out.println("  [Duzeltildi] " + s));
        }
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " icin deger gerekli");
        }
        return args[i];
    }

    private static void requireInside(Path dir, Path file) {
        if (!file.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("Dosya cikti klasorunun disinda: " + file);
        }
    }
}
