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
 * what the panel does on save, to see whether the file would break.
 *
 * <ol>
 *   <li>HTML entities: the panel decodes them (one stray apostrophe entity once killed the whole site's JS).</li>
 *   <li>windows-1254 round trip: characters outside it become '?'.</li>
 *   <li>Character budget (total) and per-slot target (a warning only; the panel's enforcement is unverified).</li>
 *   <li>JS syntax with {@code node --check} when Node is installed (skipped otherwise).</li>
 *   <li>CSS balance: braces and comment markers.</li>
 *   <li>No comments left: the files are publicly readable from the CDN.</li>
 * </ol>
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
    record Result(int length, List<String> errors, List<String> warnings) {}

    public int run() throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(cfg.outputDir())) {
            try (Stream<Path> s = Files.list(cfg.outputDir())) {
                s.filter(p -> p.toString().endsWith(".css") || p.toString().endsWith(".js"))
                        .sorted().forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            out.println(cfg.rel(cfg.outputDir()) + " bos - once: build");
            return 1;
        }
        String node = findNode();
        out.println("PANEL SIMULASYONU  (node: " + (node == null ? "yok" : node) + ")");
        out.println("=".repeat(72));

        int total = 0;
        int bad = 0;
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            Result r = check(file.getFileName().toString(), text, node);
            total += r.length();
            String name = file.getFileName().toString();
            if (r.errors().isEmpty()) {
                out.printf(Locale.ROOT, "  %-14s %7d krk   temiz%n", name, r.length());
            } else {
                bad++;
                out.printf(Locale.ROOT, "  %-14s %7d krk   HATA%n", name, r.length());
                r.errors().forEach(e -> out.println("      x " + e));
            }
            r.warnings().forEach(w -> out.println("      ! " + w));
        }
        out.println("-".repeat(72));
        out.printf(Locale.ROOT, "  TOPLAM %d / %d karakter  (%%%.1f dolu)%n", total, cfg.budget(), 100.0 * total / cfg.budget());
        if (total > cfg.budget()) {
            out.println("  x BUTCE ASILDI");
            bad++;
        }
        if (bad > 0) {
            out.println();
            out.println("  " + bad + " dosyada sorun var - PANELE YUKLEME.");
            return 1;
        }
        out.println();
        out.println("  Hepsi temiz. Panele yuklenebilir.");
        return 0;
    }

    Result check(String name, String text, String node) {
        boolean js = name.endsWith(".js");
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Matcher m = ENTITY.matcher(text);
        if (m.find()) {
            errors.add("HTML entity '" + m.group() + "': " + position(text, m.start()) + " (panel kaydederken bozacak)");
        }

        CharsetEncoder enc = Cdn.PANEL_CHARSET.newEncoder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!enc.canEncode(new String(Character.toChars(cp)))) {
                errors.add("cp1254 disi karakter '" + new String(Character.toChars(cp)) + "' -> '?' olacak ("
                        + position(text, i) + ")");
                break;
            }
            i += Character.charCount(cp);
        }

        if (js) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 127) {
                    warnings.add("JS'te ASCII disi karakter '" + text.charAt(i) + "' (" + position(text, i)
                            + ") - yorum icinde ise sorun degil, metin icindeyse kacis dizisi kullan");
                    break;
                }
            }
            if (node != null) {
                String err = nodeCheck(node, text);
                if (err != null) {
                    errors.add("JS sozdizimi HATALI (panel sonrasi hali):\n      " + err);
                }
            } else {
                warnings.add("Node bulunamadi - JS sozdizimi kontrolu atlandi");
            }
        }

        String stripped = js ? CommentStripper.js(text) : CommentStripper.css(text);
        if (!stripped.equals(text)) {
            errors.add("panel kopyasinda YORUM var (" + (text.length() - stripped.length()) + " karakter) - disaridan okunur");
        }

        if (text.length() > cfg.slotTarget()) {
            warnings.add(text.length() + " karakter, slot hedefi " + cfg.slotTarget() + "'i asiyor");
        }

        if (!js) {
            long open = text.chars().filter(c -> c == '{').count();
            long close = text.chars().filter(c -> c == '}').count();
            if (open != close) {
                errors.add("suslu parantez dengesiz: " + open + " adet '{' , " + close + " adet '}'");
            }
            int opens = count(text, "/*");
            int closes = count(text, "*/");
            if (opens != closes) {
                errors.add("yorum dengesiz: " + opens + " adet '/*' , " + closes + " adet '*/'");
            }
        }
        return new Result(text.length(), errors, warnings);
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
        return line + ". satir, " + (index - last) + ". sutun";
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
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder(node, "--check", tmp.toString()).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "node zaman asimina ugradi";
            }
            if (p.exitValue() == 0) {
                return null;
            }
            return output.lines().filter(l -> !l.isBlank()).limit(4).reduce((a, b) -> a + "\n      " + b).orElse("bilinmeyen hata");
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
