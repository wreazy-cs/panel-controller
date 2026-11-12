package io.github.wreazycs.panelcontroller.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stores one secret (the API key) on disk, locked by a 4-digit PIN.
 *
 * <p>Two layers:
 * <ol>
 *   <li>Inner: AES-256-GCM with a key derived from the PIN (PBKDF2-HMAC-SHA256, random salt).
 *       A wrong PIN fails the GCM tag check, so nothing about the PIN is stored.</li>
 *   <li>Outer: the operating system account ({@link Protector}, DPAPI on Windows). A copy of the
 *       file on another account or computer cannot be opened at all.</li>
 * </ol>
 *
 * <p>Wrong PINs are counted inside the file; after {@link #MAX_ATTEMPTS} the file is overwritten
 * and deleted. Limit: four digits are only 10,000 possibilities. Someone who can already run code
 * as this Windows user can bypass the counter and try them all. The PIN protects against a person
 * opening the app, not against malware on the account.
 *
 * <p>The file lives in %LOCALAPPDATA% (not synced by OneDrive, never inside a project or git
 * repository) and its access list allows only its owner.
 */
public final class KeyVault {
    public static final int MAX_ATTEMPTS = 5;
    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final int MAX_SECRET_CHARS = 4096;
    private static final byte[] MAGIC = {'P', 'C', 'V', '1'};
    private static final int FORMAT = 1;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path file;
    private final Protector protector;
    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public KeyVault(Path file, Protector protector, int iterations) {
        this.file = file.toAbsolutePath().normalize();
        this.protector = protector;
        this.iterations = iterations;
    }

    /** %LOCALAPPDATA%\PanelController\vault.bin (or ~/.local/share/PanelController elsewhere). */
    public static Path defaultLocation() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank()
                ? Path.of(local)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("PanelController").resolve("vault.bin");
    }

    public Path file() {
        return file;
    }

    public synchronized boolean exists() {
        return Files.isRegularFile(file);
    }

    /** Encrypts {@code secret} with {@code pin} and replaces whatever was stored. */
    public synchronized void store(char[] pin, char[] secret) throws VaultException {
        String problem = Pin.problem(pin);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        if (secret == null || secret.length == 0 || secret.length > MAX_SECRET_CHARS) {
            throw new IllegalArgumentException("Anahtar boş ya da çok uzun.");
        }
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        byte[] plain = encode(secret);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, deriveKey(pin, salt, iterations), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(iterations, salt));
            write(new Record(0, iterations, salt, iv, c.doFinal(plain)));
        } catch (GeneralSecurityException e) {
            throw new VaultException("Şifreleme başarısız.", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * Opens the vault. A wrong PIN is counted; the {@link #MAX_ATTEMPTS}th wrong PIN deletes the file.
     *
     * @return the secret; the caller must wipe it after use
     * @throws WrongPinException wrong PIN, attempts remain
     * @throws VaultWipedException wrong PIN and no attempts left; the file is gone
     */
    public synchronized char[] unlock(char[] pin) throws VaultException {
        if (!Pin.isWellFormed(pin)) {
            throw new IllegalArgumentException("PIN 4 rakam olmalı.");
        }
        Record r = read();
        byte[] plain = null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, deriveKey(pin, r.salt, r.iterations), new GCMParameterSpec(TAG_BITS, r.iv));
            c.updateAAD(aad(r.iterations, r.salt));
            plain = c.doFinal(r.ciphertext);
        } catch (AEADBadTagException e) {
            int failed = r.failed + 1;
            if (failed >= MAX_ATTEMPTS) {
                destroy();
                throw new VaultWipedException();
            }
            write(r.withFailed(failed));
            throw new WrongPinException(MAX_ATTEMPTS - failed);
        } catch (GeneralSecurityException e) {
            throw new VaultException("Kasa çözülemedi.", e);
        }
        try {
            if (r.failed != 0) {
                write(r.withFailed(0));
            }
            return decode(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** Wrong attempts recorded so far (0 when there is no vault). */
    public synchronized int failedAttempts() throws VaultException {
        return exists() ? read().failed : 0;
    }

    /** Re-encrypts the stored secret under a new PIN. The old PIN is checked (and counted) first. */
    public synchronized void changePin(char[] oldPin, char[] newPin) throws VaultException {
        String problem = Pin.problem(newPin);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        char[] secret = unlock(oldPin);
        try {
            store(newPin, secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /** Overwrites and deletes the vault file. Safe to call when there is none. */
    public synchronized void destroy() throws VaultException {
        try {
            if (Files.isRegularFile(file)) {
                long size = Files.size(file);
                try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE)) {
                    out.write(randomBytes((int) Math.min(size, 1 << 16)));
                }
            }
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new VaultException("Kasa dosyası silinemedi: " + file, e);
        }
    }

    // ---------------------------------------------------------------- format

    /** Inner (DPAPI-protected) record. */
    private record Record(int failed, int iterations, byte[] salt, byte[] iv, byte[] ciphertext) {
        Record withFailed(int n) {
            return new Record(n, iterations, salt, iv, ciphertext);
        }
    }

    private Record read() throws VaultException {
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new VaultException("Kasa dosyası okunamadı.", e);
        }
        if (all.length <= MAGIC.length || !Arrays.equals(Arrays.copyOf(all, MAGIC.length), MAGIC)) {
            throw new VaultException("Kasa dosyası tanınmıyor.");
        }
        byte[] inner = protector.unprotect(Arrays.copyOfRange(all, MAGIC.length, all.length));
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(inner))) {
            int format = in.readInt();
            if (format != FORMAT) {
                throw new VaultException("Kasa sürümü desteklenmiyor: " + format);
            }
            int failed = in.readInt();
            int iter = in.readInt();
            byte[] salt = in.readNBytes(SALT_BYTES);
            byte[] iv = in.readNBytes(IV_BYTES);
            int len = in.readInt();
            if (failed < 0 || iter < 1 || salt.length != SALT_BYTES || iv.length != IV_BYTES
                    || len < 16 || len > MAX_SECRET_CHARS * 4 + 16) {
                throw new VaultException("Kasa dosyası bozuk.");
            }
            byte[] ct = in.readNBytes(len);
            if (ct.length != len) {
                throw new VaultException("Kasa dosyası bozuk.");
            }
            return new Record(failed, iter, salt, iv, ct);
        } catch (IOException e) {
            throw new VaultException("Kasa dosyası bozuk.", e);
        } finally {
            Arrays.fill(inner, (byte) 0);
        }
    }

    private void write(Record r) throws VaultException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(FORMAT);
            out.writeInt(r.failed);
            out.writeInt(r.iterations);
            out.write(r.salt);
            out.write(r.iv);
            out.writeInt(r.ciphertext.length);
            out.write(r.ciphertext);
        } catch (IOException e) {
            throw new VaultException("Kasa yazılamadı.", e);
        }
        byte[] inner = buf.toByteArray();
        byte[] outer;
        try {
            outer = protector.protect(inner);
        } finally {
            Arrays.fill(inner, (byte) 0);
        }
        Path dir = file.getParent();
        Path tmp = null;
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                restrictToOwner(dir);
            }
            tmp = Files.createTempFile(dir, "vault-", ".tmp");
            restrictToOwner(tmp); // before any content is written
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(MAGIC);
                out.write(outer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            throw new VaultException("Kasa dosyası yazılamadı: " + file, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // the temp file holds only DPAPI-protected data
                }
            }
        }
    }

    /** Leaves a single access entry: the owner, full control. Nobody else (other users, groups) can read it. */
    static void restrictToOwner(Path p) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(p);
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(entry));
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
    }

    // ---------------------------------------------------------------- crypto

    private static SecretKeySpec deriveKey(char[] pin, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, iterations, 256);
        try {
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(k, "AES");
            } finally {
                Arrays.fill(k, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    /** Header bytes bound to the ciphertext, so salt and iteration count cannot be swapped. */
    private static byte[] aad(int iterations, byte[] salt) {
        return ByteBuffer.allocate(MAGIC.length + 8 + salt.length)
                .put(MAGIC).putInt(FORMAT).putInt(iterations).put(salt).array();
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static byte[] encode(char[] chars) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = Arrays.copyOfRange(bb.array(), bb.position(), bb.limit());
        Arrays.fill(bb.array(), (byte) 0);
        return out;
    }

    private static char[] decode(byte[] bytes) {
        CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] out = Arrays.copyOfRange(cb.array(), cb.position(), cb.limit());
        Arrays.fill(cb.array(), '\0');
        return out;
    }
}
