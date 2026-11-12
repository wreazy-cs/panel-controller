package io.github.wreazycs.panelcontroller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemCheckTest {

    @Test
    void javaIsReportedReady() {
        assertEquals(SystemCheck.State.OK, SystemCheck.java().state());
    }

    @Test
    void missingToolIsReportedWithItsPackage() {
        SystemCheck.Item i = SystemCheck.tool("Yok", "deneme", List.of("surely-not-a-command-xyz", "--version"), 1, 0, "Some.Package");
        assertEquals(SystemCheck.State.MISSING, i.state());
        assertEquals("Some.Package", i.wingetId());
    }

    @Test
    void oldVersionIsDetected() {
        // "java -version" prints e.g. 27; asking for 999 must say OLD
        SystemCheck.Item i = SystemCheck.tool("Java", "deneme", List.of("java", "--version"), 999, 0, null);
        assertEquals(SystemCheck.State.OLD, i.state());
    }

    @Test
    void brokenPythonScriptIsFound(@TempDir Path dir) throws IOException {
        if (SystemCheck.runQuiet(List.of(SystemCheck.pythonCommand(), "--version"), 10) == null) {
            return; // no Python on this machine
        }
        Path arac = Files.createDirectories(dir.resolve("arac"));
        Files.writeString(arac.resolve("iyi.py"), "print('ok')\n");
        assertEquals(SystemCheck.State.OK, SystemCheck.projectScripts(dir, true).state());
        Files.writeString(arac.resolve("bozuk.py"), "def x(:\n    pass\n");
        SystemCheck.Item bad = SystemCheck.projectScripts(dir, true);
        assertEquals(SystemCheck.State.PROBLEM, bad.state());
        assertTrue(bad.detail().contains("bozuk.py"), bad.detail());
        assertTrue(Files.notExists(arac.resolve("__pycache__")), "check must not write anything");
    }

    @Test
    void installCommandRefusesOddPackageNames() {
        assertThrows(IllegalArgumentException.class, () -> SystemCheck.installCommand("Git.Git & del x"));
        assertTrue(SystemCheck.installCommand("Git.Git").contains("Git.Git"));
    }
}
