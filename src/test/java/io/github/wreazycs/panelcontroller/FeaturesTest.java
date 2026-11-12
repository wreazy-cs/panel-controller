package io.github.wreazycs.panelcontroller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeaturesTest {

    // ---------------------------------------------------------------- git

    @Test
    void commitSubjectFollowsProjectConvention() {
        LocalDateTime thursday = LocalDateTime.of(2026, 11, 12, 14, 5);
        assertEquals("12.11.2026-Persembe-14.05 sepet duzeltildi -p", GitRepo.commitSubject("  sepet duzeltildi ", thursday));
        assertTrue(GitRepo.commitBody().contains("https://github.com/wreazy-cs/panel-controller"));
        assertFalse(GitRepo.commitBody().toLowerCase().contains("claude"));
    }

    @Test
    void porcelainStatusIsParsed() {
        String z = "## main...origin/main [ahead 2, behind 1]\0 M kaynak/genel/a.css\0?? tasks/yeni görev.md\0"
                + "R  yeni.css\0eski.css\0";
        GitRepo.Status s = GitRepo.parseStatus(z);
        assertEquals("main", s.branch());
        assertEquals(2, s.ahead());
        assertEquals(1, s.behind());
        assertEquals(3, s.changes().size());
        assertEquals("kaynak/genel/a.css", s.changes().get(0).path());
        assertEquals("değişti", s.changes().get(0).label());
        assertEquals("tasks/yeni görev.md", s.changes().get(1).path());
        assertEquals("yeni (izlenmiyor)", s.changes().get(1).label());
        assertEquals("yeni.css", s.changes().get(2).path());
    }

    @Test
    void commitOnlyTakesSelectedFiles(@TempDir Path dir) throws Exception {
        GitRepo git = new GitRepo(dir);
        if (!git.run(List.of("init", "-q"), 30).ok()) {
            return; // git not installed on this machine: nothing to test
        }
        git.run(List.of("config", "user.email", "test@example.com"), 10);
        git.run(List.of("config", "user.name", "Test"), 10);
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.txt"), "b");

        assertEquals(Problem.NOTHING_SELECTED, git.commit(List.of(), "x").failure().problem());
        assertEquals(Problem.EMPTY_MESSAGE, git.commit(List.of("a.txt"), " ").failure().problem());

        GitRepo.Result r = git.commit(List.of("a.txt"), "ilk değişiklik");
        assertTrue(r.ok(), r.output());
        GitRepo.Status s = git.status();
        assertEquals(1, s.changes().size(), "b.txt must stay uncommitted");
        assertEquals("b.txt", s.changes().get(0).path());
        String log = git.run(List.of("log", "-1", "--format=%s%n%b"), 10).output();
        assertTrue(log.contains("ilk değişiklik -p"), log);
        assertTrue(log.contains("Panel Controller ile gönderildi"), log);
    }

    // ---------------------------------------------------------------- tasks

    @Test
    void tasksAreReadFromFileNames(@TempDir Path dir) throws IOException {
        Path t = Files.createDirectories(dir.resolve("tasks"));
        Files.writeString(t.resolve("README.md"), "rules");
        Files.writeString(t.resolve("2026-09-23_popular-brands_in-progress.md"),
                "# Popular Brands strip\n\n- **Priority:** P4\n\n- [x] one\n- [~] two\n- [ ] three\n");
        Files.writeString(t.resolve("2026-09-19_to_2026-09-23_footer_done.md"), "# Footer\n");
        Files.writeString(t.resolve("notes.md"), "no state");

        List<Tasks.Task> all = Tasks.read(dir);
        assertEquals(3, all.size());
        Tasks.Task first = all.get(0);
        assertEquals(Tasks.Status.IN_PROGRESS, first.status());
        assertEquals("Popular Brands strip", first.title());
        assertEquals("P4", first.priority());
        assertEquals(1, first.checksDone());
        assertEquals(3, first.checksTotal());
        Tasks.Task done = all.stream().filter(x -> x.status() == Tasks.Status.DONE).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 23), done.end());
        assertFalse(done.status().active());
        assertTrue(all.stream().anyMatch(x -> x.status() == Tasks.Status.UNKNOWN));
    }

    // ---------------------------------------------------------------- notes, preview

    @Test
    void sessionNoteIsCreatedOnceAndNeverOverwritten(@TempDir Path dir) throws IOException {
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 9, 30);
        SessionNotes.Opened o = SessionNotes.open(dir, "samet", now);
        assertTrue(o.created());
        assertTrue(o.file().endsWith(Path.of("veri", "oturumlar", "OTURUM-2026-11-13-samet.md")));
        assertTrue(Files.readString(o.file()).contains("13.11.2026 09.30"));
        Files.writeString(o.file(), "my notes");
        assertFalse(SessionNotes.open(dir, "samet", now.plusHours(2)).created());
        assertEquals("my notes", Files.readString(o.file()));
        assertThrows(IllegalArgumentException.class, () -> SessionNotes.open(dir, "../x", now));
    }

    @Test
    void previewWrapsThePageAndRefusesOtherFolders(@TempDir Path dir) throws IOException {
        Path pages = Files.createDirectories(dir.resolve("veri/sayfalar"));
        Path page = pages.resolve("kvkk.html");
        Files.writeString(page, "<div><h1 class=\"x\">KVKK Metni</h1><p>içerik</p></div>");
        Path out = PagePreview.build(dir, page);
        String html = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(html.contains("<title>KVKK Metni</title>"));
        assertTrue(html.contains("<p>içerik</p>"));
        Path outside = Files.writeString(dir.resolve("x.html"), "x");
        assertThrows(IllegalArgumentException.class, () -> PagePreview.build(dir, outside));
    }

    // ---------------------------------------------------------------- dev server

    @Test
    void devServerServesProjectButNotGitOrBackups(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("kaynak/genel"));
        Files.writeString(dir.resolve("kaynak/genel/a.css"), "a{}");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/config"), "secret");
        Files.createDirectories(dir.resolve("veri/yedek/x"));
        Files.writeString(dir.resolve("veri/yedek/x/genel.css"), "old");

        DevServer s = new DevServer(dir);
        assertNull(s.resolve("/.git/config"));
        assertNull(s.resolve("/veri/yedek/x/genel.css"));
        assertNull(s.resolve("/../outside.txt"));
        assertNull(s.resolve("/%2e%2e/outside.txt"));
        assertNotNull(s.resolve("/kaynak/genel/a.css"));

        s.start(0); // any free port
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create(s.url("kaynak/genel/a.css"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertEquals("a{}", ok.body());
            assertEquals("*", ok.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
            assertEquals("no-store", ok.headers().firstValue("Cache-Control").orElse(""));
            HttpResponse<String> blocked = http.send(HttpRequest.newBuilder(URI.create(s.url(".git/config"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, blocked.statusCode());
        } finally {
            s.stop();
        }
        assertFalse(s.running());
    }

    @Test
    void benchWithoutScriptSaysSo(@TempDir Path dir) {
        BuildException e = assertThrows(BuildException.class, () -> DevServer.generateBench(dir));
        assertEquals(Problem.BENCH_SCRIPT_MISSING, e.finding().problem());
    }

    // ---------------------------------------------------------------- app info

    @Test
    void releaseNotesStartWithCurrentVersion() {
        assertEquals(AppInfo.VERSION, AppInfo.RELEASES.get(0).version());
        assertEquals("Samet Şafak", AppInfo.AUTHOR);
    }
}
