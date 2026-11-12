package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Panel simulation: run before every upload. Takes each file in the output directory and imitates
 * what the panel does on save, to see whether the file would break (codes PC-2xx in {@link Problem}).
 */
public final class PanelValidator {
    private static final Pattern ENTITY = Pattern.compile(
            "&(?:[a-zA-Z][a-zA-Z0-9]{1,9}|#\\d{1,6}|#[xX][0-9a-fA-F]{1,6});");

    private final ProjectConfig cfg;
    private final PrintStream out;

    public PanelValidator(ProjectConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
    }

    /** Result of checking one file. */
    public record FileCheck(String name, int length, List<Finding> errors, List<Finding> warnings) {
        public boolean clean() {
            return errors.isEmpty();
        }
    }

    /** Result of the whole simulation. {@code error} is set when nothing could be checked. */
    public record Report(List<FileCheck> files, int total, int budget, boolean nodeAvailable, Finding error) {
        public boolean budgetExceeded() {
            return total > budget;
        }

        public long badFiles() {
            return files.stream().filter(f -> !f.clean()).count();
        }

        public boolean ok() {
            return error == null && badFiles() == 0 && !budgetExceeded();
        }

        public List<Finding> allErrors() {
            List<Finding> all = new ArrayList<>();
            if (error != null) {
                all.add(error);
            }
            files.forEach(f -> all.addAll(f.errors()));
            if (budgetExceeded()) {
                all.add(Finding.of(Problem.BUDGET_EXCEEDED, "", total + " / " + budget + " karakter"));
            }
            return all;
        }

        public List<Finding> allWarnings() {
            List<Finding> all = new ArrayList<>();
            files.forEach(f -> all.addAll(f.warnings()));
            return all;
        }
    }

    public Report run() throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(cfg.outputDir())) {
            try (Stream<Path> s = Files.list(cfg.outputDir())) {
                s.filter(p -> p.toString().endsWith(".css") || p.toString().endsWith(".js"))
                        .sorted().forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            out.println(cfg.rel(cfg.outputDir()) + " bos - once: build");
            return new Report(List.of(), 0, cfg.budget(), false,
                    Finding.of(Problem.OUTPUT_EMPTY, cfg.rel(cfg.outputDir()), ""));
        }
        String node = findNode();
        out.println("PANEL SIMULASYONU  (node: " + (node == null ? "yok" : node) + ")");
        out.println("=".repeat(72));

        int total = 0;
        List<FileCheck> checks = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            FileCheck r = check(file.getFileName().toString(), text, node);
            checks.add(r);
            total += r.length();
            if (r.clean()) {
                out.printf(Locale.ROOT, "  %-14s %7d krk   temiz%n", r.name(), r.length());
            } else {
                out.printf(Locale.ROOT, "  %-14s %7d krk   HATA%n", r.name(), r.length());
                r.errors().forEach(e -> out.println("      x " + e.line()));
            }
            r.warnings().forEach(w -> out.println("      ! " + w.line()));
        }
        Report report = new Report(checks, total, cfg.budget(), node != null, null);
        out.println("-".repeat(72));
        out.printf(Locale.ROOT, "  TOPLAM %d / %d karakter  (%%%.1f dolu)%n", total, cfg.budget(), 100.0 * total / cfg.budget());
        if (report.budgetExceeded()) {
            out.println("  x BUTCE ASILDI");
        }
        out.println();
        if (!report.ok()) {
            out.println("  " + (report.badFiles() + (report.budgetExceeded() ? 1 : 0)) + " dosyada sorun var - PANELE YUKLEME.");
        } else {
            out.println("  Hepsi temiz. Panele yuklenebilir.");
        }
        return report;
    }

    FileCheck check(String name, String text, String node) {
        boolean js = name.endsWith(".js");
        List<Finding> errors = new ArrayList<>();
        List<Finding> warnings = new ArrayList<>();

        Matcher m = ENTITY.matcher(text);
        if (m.find()) {
            errors.add(Finding.of(Problem.OUT_HTML_ENTITY, name + " " + position(text, m.start()),
                    "HTML entity '" + m.group() + "' (panel kaydederken bozacak)"));
        }

        CharsetEncoder enc = Cdn.PANEL_CHARSET.newEncoder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (!enc.canEncode(ch)) {
                errors.add(Finding.of(Problem.OUT_NOT_CP1254, name + " " + position(text, i),
                        "cp1254 disi karakter '" + ch + "' -> '?' olacak"));
                break;
            }
            i += Character.charCount(cp);
        }

        if (js) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 127) {
                    warnings.add(Finding.of(Problem.OUT_JS_NON_ASCII, name + " " + position(text, i),
                            "JS'te ASCII disi karakter '" + text.charAt(i) + "'"));
                    break;
                }
            }
            if (node != null) {
                String err = nodeCheck(node, text);
                if (err != null) {
                    errors.add(Finding.of(Problem.OUT_JS_SYNTAX, name, err));
                }
            } else {
                warnings.add(Finding.of(Problem.NODE_MISSING, name, "JS sozdizimi kontrolu atlandi"));
            }
        }

        String stripped = js ? CommentStripper.js(text) : CommentStripper.css(text);
        if (!stripped.equals(text)) {
            errors.add(Finding.of(Problem.OUT_COMMENT, name,
                    "panel kopyasinda YORUM var (" + (text.length() - stripped.length()) + " karakter)"));
        }

        if (text.length() > cfg.slotTarget()) {
            warnings.add(Finding.of(Problem.SLOT_OVER_TARGET, name,
                    text.length() + " karakter, slot hedefi " + cfg.slotTarget() + "'i asiyor"));
        }

        if (!js) {
            long open = text.chars().filter(c -> c == '{').count();
            long close = text.chars().filter(c -> c == '}').count();
            if (open != close) {
                errors.add(Finding.of(Problem.OUT_BRACES, name, open + " adet '{' , " + close + " adet '}'"));
            }
            int opens = count(text, "/*");
            int closes = count(text, "*/");
            if (opens != closes) {
                errors.add(Finding.of(Problem.OUT_COMMENT_MARKERS, name, opens + " adet '/*' , " + closes + " adet '*/'"));
            }
        }
        return new FileCheck(name, text.length(), errors, warnings);
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + what.length())) {
            n++;
        }
        return n;
    }

    static String position(String text, int index) {
        int line = 1;
        int last = -1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                last = i;
            }
        }
        return line + ":" + (index - last);
    }

    // ------------------------------------------------------------------- node

    static String findNode() {
        for (String candidate : new String[] {"node", "C:\\Program Files\\nodejs\\node.exe",
                "C:\\Program Files (x86)\\nodejs\\node.exe"}) {
            try {
                Process p = new ProcessBuilder(candidate, "-v").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (IOException | InterruptedException e) {
                // try the next candidate
            }
        }
        return null;
    }

    /** @return null when the syntax is valid, otherwise the first lines of node's complaint */
    static String nodeCheck(String node, String text) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("panel-check-", ".js");
            // what the panel stores: windows-1254 round trip
            byte[] stored = text.getBytes(Cdn.PANEL_CHARSET);
            Files.writeString(tmp, new String(stored, Cdn.PANEL_CHARSET), StandardCharsets.UTF_8);
            Process p = new ProcessBuilder(node, "--check", tmp.toString()).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "node zaman asimina ugradi";
            }
            if (p.exitValue() == 0) {
                return null;
            }
            return output.lines().filter(l -> !l.isBlank()).limit(4).reduce((a, b) -> a + " | " + b).orElse("bilinmeyen hata");
        } catch (IOException e) {
            return "node calistirilamadi: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "kesildi";
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temp file cleanup is best-effort
                }
            }
        }
    }
}
