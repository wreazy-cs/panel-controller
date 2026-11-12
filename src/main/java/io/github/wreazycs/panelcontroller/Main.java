package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Command line entry point. */
public final class Main {
    private Main() {}

    static final String HELP = """
            Panel Controller - safe CSS/JS workflow for PlatinMarket stores

            Usage: panel-controller [--root <dir>] [--config <file>] <command> [args]

              build [--readable|--commented] [slot...]
                                   Build source pieces into the panel files (default: output dir).
              validate             Simulate the panel on the output files. Run before every upload.
              check                build + validate in one step.
              backup               Download the live files into the backup dir. Run before uploading.
              compare [--dir <d>]  Compare local output with the live CDN files.
              backups              List backups, newest first.
              copy <file>          Copy an output file (e.g. genel.css) to the clipboard.
              restore <stamp|latest> <file>
                                   Copy a backed-up file to the clipboard, to paste back into the panel.
              help                 This text.

            --root    project directory (default: current directory)
            --config  properties file (cdn URL, slots, directories); see panel-controller.properties.example
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
        ProjectConfig cfg = ProjectConfig.load(root, configFile);
        String cmd = rest.get(0);
        List<String> a = rest.subList(1, rest.size());

        switch (cmd) {
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
                return new Builder(cfg, out).build(mode, filters);
            }
            case "validate":
                return new PanelValidator(cfg, out).run();
            case "check": {
                int rc = new Builder(cfg, out).build(Builder.Mode.PANEL, List.of());
                if (rc != 0) {
                    return rc;
                }
                out.println();
                return new PanelValidator(cfg, out).run();
            }
            case "backup":
                return new Backup(cfg, out).run(new Cdn(cfg.cdnTemplate()));
            case "compare": {
                Path dir = cfg.outputDir();
                if (a.size() >= 2 && a.get(0).equals("--dir")) {
                    dir = root.resolve(a.get(1));
                }
                return new Comparer(cfg, out).run(new Cdn(cfg.cdnTemplate()), dir);
            }
            case "backups": {
                List<String> all = new Backup(cfg, out).list();
                if (all.isEmpty()) {
                    out.println("Yedek yok: " + cfg.rel(cfg.backupDir()));
                }
                all.forEach(out::println);
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
                Optional<Path> file = new Backup(cfg, out).find(a.get(0), a.get(1));
                if (file.isEmpty()) {
                    out.println("Yedek bulunamadi: " + a.get(0) + " / " + a.get(1) + "   ('backups' ile listele)");
                    return 1;
                }
                int n = Clipboard.copyFile(file.get());
                out.println(cfg.rel(file.get()) + " panoya kopyalandi (" + n + " karakter).");
                out.println("Panelde ilgili slota yapistirip kaydet; sonra 'compare' ile dogrula.");
                return 0;
            }
            default:
                out.println("Bilinmeyen komut: " + cmd);
                out.print(HELP);
                return 2;
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
