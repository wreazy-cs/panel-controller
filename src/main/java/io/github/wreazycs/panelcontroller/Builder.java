package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Joins the small source pieces of each slot into the single file the panel accepts.
 *
 * <p>Rules enforced while building (a violation stops the build):
 * <ol>
 *   <li>JS sources are pure ASCII (non-ASCII is written as backslash-u escapes).</li>
 *   <li>No HTML entities anywhere (the panel decodes them and corrupts the file).</li>
 *   <li>Every character fits in windows-1254 (the panel stores single bytes).</li>
 *   <li>First line of every piece is a meta line: {@code /*@vt <no> | <title> | ezer: <what it overrides> *}{@code /}</li>
 *       with <no> unique across all slots.
 *   <li>A JS piece ends with ';' and has no multi-line strings or template literals.</li>
 *   <li>CSS compression re-parses its result and must equal the uncompressed rules.</li>
 *   <li>The panel copy contains no comments.</li>
 * </ol>
 */
public final class Builder {

    public enum Mode {
        /** Minified, comment-free; this is what gets pasted into the panel. */
        PANEL("cikti"),
        /** Comment-free but readable, with piece headers and a table of contents. Not for the panel. */
        READABLE("okunur"),
        /** Keeps source comments, with headers. Not for the panel. */
        COMMENTED("yorumlu");

        final String dirName;

        Mode(String dirName) {
            this.dirName = dirName;
        }
    }

    private static final Pattern META = Pattern.compile(
            "^/\\*@vt\\s+([^|]+?)\\s*\\|\\s*(.+?)\\s*(?:\\|\\s*ezer:\\s*(.+?)\\s*)?\\*/\\s*$");
    private static final Pattern ENTITY = Pattern.compile(
            "&(?:[a-zA-Z][a-zA-Z0-9]{1,9}|#\\d{1,6}|#[xX][0-9a-fA-F]{1,6});");

    /** One source piece. */
    static final class Piece {
        final String file;
        final String no;
        final String title;
        String body;
        int startLine;

        Piece(String file, String no, String title, String body) {
            this.file = file;
            this.no = no;
            this.title = title;
            this.body = body;
        }
    }

    private final ProjectConfig cfg;
    private final PrintStream out;

    public Builder(ProjectConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
    }

    /** @return process exit code (0 = success) */
    public int build(Mode mode, List<String> filters) throws IOException {
        Path target = mode == Mode.PANEL ? cfg.outputDir() : cfg.generatedDir().resolve(mode.dirName);
        Files.createDirectories(target);
        out.printf("Panel Controller - build  [%s -> %s/]%n", mode.name(), cfg.rel(target));
        out.println("=".repeat(72));

        int total = 0;
        Map<String, String> seen = new HashMap<>();
        try {
            for (Slot slot : cfg.slots()) {
                List<Piece> pieces = readPieces(slot, mode == Mode.COMMENTED);
                for (Piece p : pieces) {
                    String prev = seen.putIfAbsent(p.no, p.file);
                    if (prev != null) {
                        throw new BuildException("IC-VT-" + p.no + " iki kez var: " + prev + " ve " + p.file);
                    }
                }
                if (!filters.isEmpty() && filters.stream().noneMatch(slot.folder()::contains)) {
                    continue;
                }
                Path file = target.resolve(slot.outputName());
                if (pieces.isEmpty()) {
                    Files.deleteIfExists(file); // slot emptied: do not leave a stale output behind
                    continue;
                }
                String text = mode == Mode.PANEL
                        ? panelCopy(pieces, slot.isJs())
                        : reviewCopy(slot, pieces);
                Files.writeString(file, text, StandardCharsets.UTF_8);
                total += text.length();
                String warn = mode == Mode.PANEL && text.length() > cfg.slotTarget()
                        ? "  !! " + cfg.slotTarget() + " HEDEFI ASIYOR" : "";
                out.printf(Locale.ROOT, "  %-14s <- %2d parca   %7d karakter  %%%5.1f   (slot: %s)%s%n",
                        slot.outputName(), pieces.size(), text.length(),
                        100.0 * text.length() / cfg.slotTarget(), slot.panelName(), warn);
            }
        } catch (BuildException e) {
            out.println();
            out.println("DERLEME DURDU");
            out.println("  " + e.getMessage());
            out.println();
            return 1;
        }

        out.println("-".repeat(72));
        out.printf(Locale.ROOT, "  TOPLAM %d karakter  /  %d butce  (%%%.1f dolu)   [yuzdeler: slot hedefi %d]%n",
                total, cfg.budget(), 100.0 * total / cfg.budget(), cfg.slotTarget());
        if (mode == Mode.PANEL && total > cfg.budget()) {
            out.println("  !! BUTCE ASILDI");
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------ reading/checks

    List<Piece> readPieces(Slot slot, boolean keepComments) throws IOException, BuildException {
        Path dir = cfg.sourceDir().resolve(slot.folder());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        String ext = slot.isJs() ? ".js" : ".css";
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(ext)).sorted().toList();
        }
        List<Piece> pieces = new ArrayList<>();
        for (Path p : files) {
            String raw = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (raw.startsWith("﻿")) {
                raw = raw.substring(1);
            }
            String rel = cfg.rel(p);
            check(rel, raw, slot.isJs());
            String[] lines = raw.split("\n", -1);
            Matcher m = META.matcher(lines[0]);
            if (!m.matches()) {
                throw new BuildException(rel + "  ilk satirda /*@vt ... */ meta satiri yok");
            }
            String body = String.join("\n", List.of(lines).subList(1, lines.length));
            if (!keepComments) {
                body = CommentStripper.collapseBlankLines(
                        slot.isJs() ? CommentStripper.js(body) : CommentStripper.css(body));
            }
            pieces.add(new Piece(rel, m.group(1), m.group(2), body));
        }
        return pieces;
    }

    /** Panel safety checks on one source file. */
    static void check(String path, String text, boolean js) throws BuildException {
        CharsetEncoder cp1254 = java.nio.charset.Charset.forName("windows-1254").newEncoder();
        String[] lines = text.split("\n", -1);
        for (int no = 1; no <= lines.length; no++) {
            String line = lines[no - 1];
            if (js) {
                for (int col = 0; col < line.length(); col++) {
                    char ch = line.charAt(col);
                    if (ch > 127) {
                        throw new BuildException(String.format("%s:%d:%d  JS kaynaginda ASCII disi karakter '%s' -> \\u%04x yaz",
                                path, no, col + 1, ch, (int) ch));
                    }
                }
            }
            Matcher m = ENTITY.matcher(line);
            if (m.find()) {
                throw new BuildException(String.format("%s:%d  HTML entity bulundu: %s  (panel bunu cozup dosyayi bozar)",
                        path, no, m.group()));
            }
            for (int col = 0; col < line.length(); ) {
                int cp = line.codePointAt(col);
                if (!cp1254.canEncode(new String(Character.toChars(cp)))) {
                    throw new BuildException(String.format("%s:%d:%d  cp1254'te olmayan karakter '%s' (panel bunu '?' yapar)",
                            path, no, col + 1, new String(Character.toChars(cp))));
                }
                col += Character.charCount(cp);
            }
        }
    }

    // ------------------------------------------------------------- panel copy

    static String panelPiece(Piece p, boolean js) throws BuildException {
        String g = p.body;
        if (js) {
            if (!g.stripTrailing().endsWith(";")) {
                throw new BuildException(p.file + "  JS parcasi ';' ile bitmiyor (parcalar bitisince bir sonrakiyle birlesip bozulabilir)");
            }
            String problem = Minifier.jsLineProblem(g);
            if (problem != null) {
                throw new BuildException(p.file + "  " + problem + " - girinti silme guvenli degil");
            }
            return Minifier.js(g);
        }
        String compact;
        try {
            compact = Minifier.css(g);
            if (!Minifier.cssSummary(compact).equals(Minifier.cssSummary(g))) {
                throw new BuildException(p.file + "  CSS sikistirma denetimi TUTMADI (kural farki)");
            }
        } catch (IllegalArgumentException e) {
            throw new BuildException(p.file + "  CSS ayristirilamadi: " + e.getMessage());
        }
        return compact;
    }

    static String panelCopy(List<Piece> pieces, boolean js) throws BuildException {
        StringBuilder sb = new StringBuilder();
        for (Piece p : pieces) {
            sb.append(panelPiece(p, js)).append('\n');
        }
        String body = sb.toString();
        String left = js ? CommentStripper.js(body) : CommentStripper.css(body);
        if (!left.equals(body)) {
            throw new BuildException("panel kopyasinda yorum kaldi (" + (body.length() - left.length()) + " karakter)");
        }
        return body;
    }

    // ------------------------------------------------------------ review copy

    private static String pieceHeader(Piece p) {
        return "\n/* [IC-VT-" + p.no + "] " + p.title + " | kaynak: " + p.file + " */\n";
    }

    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }

    String reviewCopy(Slot slot, List<Piece> pieces) {
        long line = 1;
        StringBuilder body = new StringBuilder();
        for (Piece p : pieces) {
            String head = pieceHeader(p);
            p.startLine = (int) (line + count(head, '\n'));
            body.append(head).append(p.body);
            line += count(head, '\n') + count(p.body, '\n');
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        int offset = (int) count(banner(slot, pieces, index(pieces, 0), stamp), '\n');
        return banner(slot, pieces, index(pieces, offset), stamp) + body;
    }

    private static List<String> index(List<Piece> pieces, int offset) {
        List<String> rows = new ArrayList<>();
        for (Piece p : pieces) {
            String label = "[IC-VT-" + p.no + "] " + p.title + " ";
            rows.add(String.format(Locale.ROOT, "     %s%s satir %d", label,
                    ".".repeat(Math.max(2, 58 - label.length())), p.startLine + offset));
        }
        return rows;
    }

    private String banner(Slot slot, List<Piece> pieces, List<String> index, String stamp) {
        List<String> top = new ArrayList<>(List.of(
                "/* =====================================================================",
                "   Panel Controller - " + slot.outputName() + "   (inceleme kopyasi - PANELE YAPISTIRMA)",
                "   Panel kopyasi: " + cfg.rel(cfg.outputDir()) + "/" + slot.outputName() + "   |   slot: " + slot.panelName(),
                "   Kaynak   : " + cfg.rel(cfg.sourceDir().resolve(slot.folder())) + "/ altindaki " + pieces.size()
                        + " parca   |   " + stamp,
                "",
                "   ICINDEKILER  (aramak icin: IC-VT-<no>)"));
        top.addAll(index);
        top.add("   ===================================================================== */");
        top.add("");
        return String.join("\n", top) + "\n";
    }
}
