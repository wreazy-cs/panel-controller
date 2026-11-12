package io.github.wreazycs.panelcontroller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStatusTest {

    private static ProjectStatus.SlotRow genelCss(ProjectConfig cfg) throws IOException {
        return ProjectStatus.slotRows(cfg).get(0);
    }

    @Test
    void stateFollowsSourceAndOutputTimes(@TempDir Path dir) throws IOException {
        ProjectConfig cfg = ProjectConfig.load(dir, null);
        assertEquals(ProjectStatus.State.EMPTY, genelCss(cfg).state());

        Path src = Files.createDirectories(dir.resolve("kaynak/genel")).resolve("a.css");
        Files.writeString(src, "/*@vt 1 | A */\na{x:y}\n");
        assertEquals(ProjectStatus.State.NO_OUTPUT, genelCss(cfg).state());

        Path out = Files.createDirectories(dir.resolve("cikti")).resolve("genel.css");
        Files.writeString(out, "a{x:y}\n");
        Files.setLastModifiedTime(src, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(out, FileTime.fromMillis(2_000_000));
        ProjectStatus.SlotRow ok = genelCss(cfg);
        assertEquals(ProjectStatus.State.OK, ok.state());
        assertEquals(1, ok.pieces());
        assertEquals(7, ok.outputChars());

        Files.setLastModifiedTime(src, FileTime.fromMillis(3_000_000));
        assertEquals(ProjectStatus.State.STALE, genelCss(cfg).state());
    }

    @Test
    void overTargetIsReported(@TempDir Path dir) throws IOException {
        Path props = dir.resolve("p.properties");
        Files.writeString(props, "slotTarget=5\n");
        ProjectConfig cfg = ProjectConfig.load(dir, props);
        Path src = Files.createDirectories(dir.resolve("kaynak/genel")).resolve("a.css");
        Files.writeString(src, "/*@vt 1 | A */\na{x:y}\n");
        Path out = Files.createDirectories(dir.resolve("cikti")).resolve("genel.css");
        Files.writeString(out, "a{x:y}\n");
        Files.setLastModifiedTime(src, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(out, FileTime.fromMillis(2_000_000));
        assertEquals(ProjectStatus.State.OVER, genelCss(cfg).state());
    }

    @Test
    void backupAgeUsesNewestStamp(@TempDir Path dir) throws IOException {
        ProjectConfig cfg = ProjectConfig.load(dir, null);
        assertTrue(ProjectStatus.backupAge(cfg, Clock.systemDefaultZone()).isEmpty());

        Files.createDirectories(cfg.backupDir().resolve("2026-11-12_10-00"));
        Files.createDirectories(cfg.backupDir().resolve("2026-11-12_12-30"));
        Files.createDirectories(cfg.backupDir().resolve("not-a-backup"));
        ZoneId zone = ZoneId.systemDefault();
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 11, 12, 13, 0).atZone(zone).toInstant(), zone);
        assertEquals(Duration.ofMinutes(30), ProjectStatus.backupAge(cfg, clock).orElseThrow());
    }

    @Test
    void ageIsDescribedInTurkish() {
        assertEquals("az önce", ProjectStatus.describeAge(Duration.ofSeconds(20)));
        assertEquals("30 dk önce", ProjectStatus.describeAge(Duration.ofMinutes(30)));
        assertEquals("5 saat önce", ProjectStatus.describeAge(Duration.ofHours(5)));
        assertEquals("3 gün önce", ProjectStatus.describeAge(Duration.ofDays(3)));
    }

    @Test
    void gitStatusLineIsSummarised() {
        assertEquals("main · temiz", ProjectStatus.describeGit("## main...origin/main\n"));
        assertEquals("main · 2 değişiklik · 1 push edilmemiş",
                ProjectStatus.describeGit("## main...origin/main [ahead 1]\n M a.txt\n?? b.txt\n"));
        assertEquals("dev · temiz", ProjectStatus.describeGit("## dev\n"));
        assertEquals(List.of("git"), List.of(ProjectStatus.describeGit("")));
    }
}
