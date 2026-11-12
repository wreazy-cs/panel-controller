package io.github.wreazycs.panelcontroller.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class KeyVaultTest {
    private static final char[] KEY = "sk-test-0123456789abcdef".toCharArray();

    /** Stand-in for DPAPI: reversible, and fails on data it did not produce. */
    static final class FakeProtector implements Protector {
        @Override
        public byte[] protect(byte[] d) {
            byte[] out = new byte[d.length + 1];
            out[0] = 42;
            for (int i = 0; i < d.length; i++) {
                out[i + 1] = (byte) (d[i] ^ 0x5A);
            }
            return out;
        }

        @Override
        public byte[] unprotect(byte[] d) throws VaultException {
            if (d.length == 0 || d[0] != 42) {
                throw new VaultException("not ours");
            }
            byte[] out = new byte[d.length - 1];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (d[i + 1] ^ 0x5A);
            }
            return out;
        }
    }

    private static KeyVault vault(Path dir) {
        return new KeyVault(dir.resolve("PanelController/vault.bin"), new FakeProtector(), 1000);
    }

    @Test
    void storeThenUnlock(@TempDir Path dir) throws Exception {
        KeyVault v = vault(dir);
        assertFalse(v.exists());
        v.store("2580".toCharArray(), KEY);
        assertTrue(v.exists());
        assertArrayEquals(KEY, v.unlock("2580".toCharArray()));
    }

    @Test
    void fileNeverContainsTheKey(@TempDir Path dir) throws Exception {
        KeyVault v = vault(dir);
        v.store("2580".toCharArray(), KEY);
        String raw = new String(Files.readAllBytes(v.file()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("sk-test"));
        assertFalse(raw.contains("0123456789"));
    }

    @Test
    void wrongPinsAreCountedAcrossRestartsAndWipeAtFive(@TempDir Path dir) throws Exception {
        vault(dir).store("2580".toCharArray(), KEY);
        for (int i = 1; i < KeyVault.MAX_ATTEMPTS; i++) {
            KeyVault fresh = vault(dir); // new instance: the counter must come from the file
            WrongPinException e = assertThrows(WrongPinException.class, () -> fresh.unlock("9999".toCharArray()));
            assertEquals(KeyVault.MAX_ATTEMPTS - i, e.remaining());
        }
        KeyVault last = vault(dir);
        assertThrows(VaultWipedException.class, () -> last.unlock("9999".toCharArray()));
        assertFalse(last.exists());
        assertFalse(Files.exists(last.file()));
    }

    @Test
    void correctPinResetsTheCounter(@TempDir Path dir) throws Exception {
        KeyVault v = vault(dir);
        v.store("2580".toCharArray(), KEY);
        assertThrows(WrongPinException.class, () -> v.unlock("1357".toCharArray()));
        assertThrows(WrongPinException.class, () -> v.unlock("1357".toCharArray()));
        assertEquals(2, v.failedAttempts());
        v.unlock("2580".toCharArray());
        assertEquals(0, v.failedAttempts());
    }

    @Test
    void changePinRequiresOldPin(@TempDir Path dir) throws Exception {
        KeyVault v = vault(dir);
        v.store("2580".toCharArray(), KEY);
        assertThrows(WrongPinException.class, () -> v.changePin("0000".toCharArray(), "7391".toCharArray()));
        v.changePin("2580".toCharArray(), "7391".toCharArray());
        assertThrows(WrongPinException.class, () -> v.unlock("2580".toCharArray()));
        assertArrayEquals(KEY, v.unlock("7391".toCharArray()));
    }

    @Test
    void destroyRemovesTheFile(@TempDir Path dir) throws Exception {
        KeyVault v = vault(dir);
        v.store("2580".toCharArray(), KEY);
        v.destroy();
        assertFalse(v.exists());
        v.destroy(); // no vault: no error
    }

    @Test
    void tamperedFileIsRejectedNotCounted(@TempDir Path dir) throws Exception {
        KeyVault v = vault(dir);
        v.store("2580".toCharArray(), KEY);
        byte[] b = Files.readAllBytes(v.file());
        b[0] = 'X';
        Files.write(v.file(), b);
        VaultException e = assertThrows(VaultException.class, () -> v.unlock("2580".toCharArray()));
        assertFalse(e instanceof WrongPinException);
    }

    @Test
    void fileFromAnotherAccountIsRejected(@TempDir Path dir) throws Exception {
        KeyVault v = vault(dir);
        v.store("2580".toCharArray(), KEY);
        Protector other = new Protector() {
            public byte[] protect(byte[] d) {
                return d;
            }

            public byte[] unprotect(byte[] d) throws VaultException {
                throw new VaultException("other account");
            }
        };
        KeyVault foreign = new KeyVault(v.file(), other, 1000);
        assertThrows(VaultException.class, () -> foreign.unlock("2580".toCharArray()));
    }

    @Test
    void weakOrMalformedPinsAreRefused() {
        assertNull(Pin.problem("2580".toCharArray()));
        assertNull(Pin.problem("1357".toCharArray()));
        assertNotNull(Pin.problem("123".toCharArray()));
        assertNotNull(Pin.problem("12a4".toCharArray()));
        assertNotNull(Pin.problem("1111".toCharArray()));
        assertNotNull(Pin.problem("1234".toCharArray()));
        assertNotNull(Pin.problem("9876".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> new KeyVault(Path.of("x"), new FakeProtector(), 1)
                .store("1234".toCharArray(), KEY));
    }

    @Test
    void sessionNeverPrintsTheKey() {
        KeySession s = new KeySession();
        s.set(KEY, false);
        assertFalse(s.toString().contains("sk-test"));
        assertEquals("••••••••cdef", s.masked());
        s.clear();
        assertFalse(s.hasKey());
        assertEquals("yok", s.masked());
    }

    // ------------------------------------------------ real Windows integration

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void realDpapiRoundTripAndOwnerOnlyAcl(@TempDir Path dir) throws Exception {
        KeyVault v = new KeyVault(dir.resolve("PanelController/vault.bin"), Protector.forPlatform().orElseThrow(), 1000);
        v.store("2580".toCharArray(), KEY);
        assertArrayEquals(KEY, v.unlock("2580".toCharArray()));

        List<AclEntry> acl = Files.getFileAttributeView(v.file(), AclFileAttributeView.class).getAcl();
        assertEquals(1, acl.size(), acl.toString());
        assertEquals(Files.getOwner(v.file()), acl.get(0).principal());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dpapiDataIsNotReadableWithoutDpapi(@TempDir Path dir) throws Exception {
        KeyVault real = new KeyVault(dir.resolve("v.bin"), Protector.forPlatform().orElseThrow(), 1000);
        real.store("2580".toCharArray(), KEY);
        KeyVault fake = new KeyVault(dir.resolve("v.bin"), new FakeProtector(), 1000);
        assertThrows(VaultException.class, () -> fake.unlock("2580".toCharArray()));
    }

    @Test
    void defaultLocationIsOutsideTheProject() throws IOException {
        Path p = KeyVault.defaultLocation();
        assertTrue(p.endsWith(Path.of("PanelController", "vault.bin")));
        assertFalse(p.toString().contains("OneDrive"), p.toString());
    }
}
