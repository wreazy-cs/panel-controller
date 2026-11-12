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
