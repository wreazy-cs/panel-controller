package io.github.wreazycs.panelcontroller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {

    // ---- CommentStripper

    @Test
    void cssCommentsRemovedButUrlAndStringsKept() {
        String in = "a{background:url(data:image/png;base64,/*x*/AA)} /* gone */ b{content:\"/* keep */\"}";
        assertEquals("a{background:url(data:image/png;base64,/*x*/AA)}  b{content:\"/* keep */\"}",
                CommentStripper.css(in));
    }

    @Test
    void jsCommentsRemovedButUrlsAndRegexKept() {
        String in = "var u='http://x.y'; // note\nvar r=/a\\/\\/b/; /* c */ var d=4/2;";
        assertEquals("var u='http://x.y'; \nvar r=/a\\/\\/b/;  var d=4/2;", CommentStripper.js(in));
    }

    // ---- Minifier

    @Test
    void cssIsCompactedToOneRulePerLine() {
        String in = "a , b >  c {\n  color : red ;\n  margin: 0  auto !important;\n}\n@media (min-width: 10px) {\n a{x:y}\n}";
        assertEquals("a,b>c{color:red;margin:0 auto!important}\n@media (min-width:10px){\na{x:y}\n}", Minifier.css(in));
    }

    @Test
    void cssKeepsVarValuesAndStringsVerbatim() {
        String in = "a{--x:  1px   2px; content:\"a   b\"; w: calc(1px  +  2px)}";
        assertEquals("a{--x:1px   2px;content:\"a   b\";w:calc(1px + 2px)}", Minifier.css(in));
    }

    @Test
    void cssSummaryIsWhitespaceIndependent() {
        String a = "a { color : red ; }\n@media (x) { b{c:d} }";
        assertEquals(Minifier.cssSummary(a), Minifier.cssSummary(Minifier.css(a)));
    }

    @Test
    void jsLoseIndentAndBlankLines() {
        assertEquals("a();\nb();", Minifier.js("  a();\n\n    b();  \n"));
    }

    @Test
    void jsLineProblemDetectsTemplateAndMultilineStrings() {
        assertNotNull(Minifier.jsLineProblem("var a=`x`;"));
        assertNotNull(Minifier.jsLineProblem("var a='x\ny';"));
        assertNull(Minifier.jsLineProblem("var a='x'; var r=/[/]'/;"));
    }

    @Test
    void unbalancedCssIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Minifier.css("a{color:red"));
    }

    // ---- Builder checks

    @Test
    void checkRejectsEntitiesAndNonAsciiJsAndNonCp1254() throws BuildException {
        assertThrows(BuildException.class, () -> Builder.check("f.js", "var a='&#39;';", true));
        assertThrows(BuildException.class, () -> Builder.check("f.js", "var a='ç';", true));
        assertThrows(BuildException.class, () -> Builder.check("f.css", "a{content:'≤'}", false));
        Builder.check("f.css", "a{content:'çğış'}", false); // Turkish letters are fine in CSS
    }

    @Test
    void jsPieceMustEndWithSemicolon() {
        Builder.Piece p = new Builder.Piece("f.js", "1", "t", "var a = 1");
        assertThrows(BuildException.class, () -> Builder.panelPiece(p, true));
    }

    // ---- Diff

    @Test
    void diffShowsOnlyChangedLines() {
        List<String> d = Comparer.diff(new String[] {"a", "b", "c"}, new String[] {"a", "x", "c"});
        assertEquals(List.of("-b", "+x"), d);
    }

    private static String[] lines(int n) {
        String[] a = new String[n];
        for (int i = 0; i < n; i++) a[i] = "line " + (i + 1);
        return a;
    }

    private static List<Comparer.Line> view(String[] live, String[] local) {
        return Comparer.hunks(live, local, Comparer.align(live, local), 3);
    }

    @Test
    void diffViewKeepsContextAndNumbersLines() {
        String[] live = lines(100);
        String[] local = lines(100);
        local[49] = "changed";
        List<Comparer.Line> v = view(live, local);
        // 46 lines left out, 3 before, - and +, 3 after, 47 left out
        assertEquals(10, v.size());
        assertEquals(new Comparer.Line('~', 0, 0, "46"), v.get(0));
        assertEquals(new Comparer.Line(' ', 47, 47, "line 47"), v.get(1));
        assertEquals(new Comparer.Line('-', 50, 0, "line 50"), v.get(4));
        assertEquals(new Comparer.Line('+', 0, 50, "changed"), v.get(5));
        assertEquals(new Comparer.Line(' ', 53, 53, "line 53"), v.get(8));
        assertEquals(new Comparer.Line('~', 0, 0, "47"), v.get(9));
    }

    @Test
    void diffViewNumbersFollowEachFileWhenLinesAreAdded() {
        String[] live = lines(10);
        String[] local = new String[12];
        System.arraycopy(live, 0, local, 0, 5);
        local[5] = "new a";
        local[6] = "new b";
        System.arraycopy(live, 5, local, 7, 5);
        List<Comparer.Line> v = view(live, local);
        assertEquals(List.of(
                new Comparer.Line(' ', 3, 3, "line 3"), new Comparer.Line(' ', 4, 4, "line 4"), new Comparer.Line(' ', 5, 5, "line 5"),
                new Comparer.Line('+', 0, 6, "new a"), new Comparer.Line('+', 0, 7, "new b"),
                new Comparer.Line(' ', 6, 8, "line 6"), new Comparer.Line(' ', 7, 9, "line 7"), new Comparer.Line(' ', 8, 10, "line 8"),
                new Comparer.Line('~', 0, 0, "2")), v.subList(1, v.size()));
        assertEquals(new Comparer.Line('~', 0, 0, "2"), v.get(0));
    }

    @Test
    void diffViewMergesNearbyChangesAndSkipsFarOnes() {
        String[] live = lines(60);
        String[] local = lines(60);
        local[9] = "a";
        local[13] = "b"; // 3 unchanged lines between: one block
        local[40] = "c"; // far away: its own block
        List<Comparer.Line> v = view(live, local);
        long gaps = v.stream().filter(l -> l.kind() == '~').count();
        assertEquals(3, gaps); // start, between the blocks, end
        assertEquals(6, v.stream().filter(l -> l.kind() == '+' || l.kind() == '-').count());
        assertTrue(v.stream().noneMatch(l -> l.kind() == '~' && l.text().equals("0")));
    }

    @Test
    void diffViewAtTheStartAndEndOfAFile() {
        String[] live = lines(20);
        String[] local = lines(20);
        local[0] = "first";
        local[19] = "last";
        List<Comparer.Line> v = view(live, local);
        assertEquals('-', v.get(0).kind()); // no gap marker before a change on line 1
        assertEquals('+', v.get(v.size() - 1).kind()); // nor after a change on the last line
        assertEquals(1, v.stream().filter(l -> l.kind() == '~').count(), "only the middle is left out");
        assertEquals(4, v.stream().filter(l -> l.kind() == '+' || l.kind() == '-').count());
    }

    // ---- ProjectConfig / Backup

    @Test
    void slotsAreParsedFromConfig() {
        List<Slot> s = ProjectConfig.parseSlots("a:a.css:CSS A;b-js:b.js");
        assertEquals(2, s.size());
        assertEquals("CSS A", s.get(0).panelName());
        assertTrue(s.get(1).isJs());
        assertEquals("b.js", s.get(1).panelName());
    }

    @Test
    void backupRefusesPathTraversal(@TempDir Path dir) throws IOException {
        ProjectConfig cfg = ProjectConfig.load(dir, null);
        Files.createDirectories(cfg.backupDir().resolve("2026-01-01_00-00"));
        Files.writeString(dir.resolve("secret.txt"), "x");
        Backup b = new Backup(cfg, new PrintStream(new ByteArrayOutputStream()));
        assertTrue(b.find("2026-01-01_00-00", "..\\..\\..\\secret.txt").isEmpty());
        assertTrue(b.find("2026-01-01_00-00", "../../../secret.txt").isEmpty());
    }

    // ---- End to end: build + validate on a tiny project

    @Test
    void buildThenValidateEndToEnd(@TempDir Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("kaynak/genel"));
        Files.writeString(src.resolve("10-a.css"),
                "/*@vt 10 | Test | ezer: tema */\n/* comment */\n.ic-a {\n  color : red;\n}\n");
        Path js = Files.createDirectories(dir.resolve("kaynak/genel-js"));
        Files.writeString(js.resolve("10-a.js"),
                "/*@vt 11 | Test JS | ezer: - */\n// c\nvar a = 1;\n");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        ProjectConfig cfg = ProjectConfig.load(dir, null);

        Builder.Report built = new Builder(cfg, out).build(Builder.Mode.PANEL, List.of());
        assertTrue(built.ok(), buf.toString());
        assertEquals(2, built.outputs().size());
        assertEquals(".ic-a{color:red}\n", Files.readString(dir.resolve("cikti/genel.css")));
        assertEquals("var a = 1;\n", Files.readString(dir.resolve("cikti/genel.js")));
        assertTrue(new PanelValidator(cfg, out).run().ok(), buf.toString());
    }

    @Test
    void buildFailureCarriesItsCode(@TempDir Path dir) throws IOException {
        Path js = Files.createDirectories(dir.resolve("kaynak/genel-js"));
        Files.writeString(js.resolve("10-a.js"), "/*@vt 11 | T */\nvar a = 'ç';\n");
        ProjectConfig cfg = ProjectConfig.load(dir, null);
        Builder.Report r = new Builder(cfg, new PrintStream(new ByteArrayOutputStream())).build(Builder.Mode.PANEL, List.of());
        assertFalse(r.ok());
        assertEquals(Problem.JS_NON_ASCII, r.failure().problem());
        assertTrue(r.failure().line().startsWith("PC-101"));
    }

    @Test
    void validatorReportsEmptyOutputAndBadCss(@TempDir Path dir) throws IOException {
        ProjectConfig cfg = ProjectConfig.load(dir, null);
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
        assertEquals(Problem.OUTPUT_EMPTY, new PanelValidator(cfg, quiet).run().error().problem());

        Files.createDirectories(dir.resolve("cikti"));
        Files.writeString(dir.resolve("cikti/genel.css"), "a{color:red}\nb{x:y\n");
        PanelValidator.Report r = new PanelValidator(cfg, quiet).run();
        assertFalse(r.ok());
        assertEquals(Problem.OUT_BRACES, r.allErrors().get(0).problem());
    }

    @Test
    void pasteOrderPutsGeneralLast(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("cikti"));
        for (String f : List.of("genel.css", "genel.js", "sepet.js", "anasayfa.css", "urun.css")) {
            Files.writeString(dir.resolve("cikti").resolve(f), "x");
        }
        ProjectConfig cfg = ProjectConfig.load(dir, null);
        assertEquals(List.of("anasayfa.css", "sepet.js", "urun.css", "genel.css", "genel.js"),
                Workflow.pasteOrder(cfg).stream().map(Slot::outputName).toList());
    }

    @Test
    void prepareStopsWithoutCdn(@TempDir Path dir) throws IOException {
        ProjectConfig cfg = ProjectConfig.load(dir, null);
        Workflow.PrepareReport r = Workflow.prepare(cfg, new PrintStream(new ByteArrayOutputStream()));
        assertFalse(r.ok());
        assertEquals(Workflow.Step.BACKUP, r.failedAt());
        assertEquals(Problem.CDN_NOT_SET, r.failure().problem());
        assertTrue(r.build() == null && r.validation() == null, "later steps must not run");
    }

    @Test
    void everyProblemHasAUniqueCode() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Problem p : Problem.values()) {
            assertTrue(p.code().matches("PC-\\d{3}"), p.code());
            assertTrue(seen.add(p.code()), "duplicate " + p.code());
            assertFalse(p.title().isBlank() || p.meaning().isBlank() || p.fix().isBlank(), p.code());
        }
    }

    @Test
    void duplicateNumbersStopTheBuild(@TempDir Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("kaynak/genel"));
        Files.writeString(src.resolve("a.css"), "/*@vt 1 | A */\na{x:y}\n");
        Files.writeString(src.resolve("b.css"), "/*@vt 1 | B */\nb{x:y}\n");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ProjectConfig cfg = ProjectConfig.load(dir, null);
        Builder.Report r = new Builder(cfg, new PrintStream(buf, true, StandardCharsets.UTF_8)).build(Builder.Mode.PANEL, List.of());
        assertFalse(r.ok());
        assertEquals(Problem.DUPLICATE_NUMBER, r.failure().problem());
        assertTrue(buf.toString().contains("iki kez var"));
        assertFalse(Files.exists(dir.resolve("cikti/genel.css")));
    }
}
