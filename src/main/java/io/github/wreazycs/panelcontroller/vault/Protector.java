package io.github.wreazycs.panelcontroller.vault;

import java.util.Locale;
import java.util.Optional;

/**
 * Outer encryption layer bound to the operating system account. On Windows this is DPAPI: data
 * protected by one Windows user can only be unprotected by the same user on the same machine.
 */
public interface Protector {

    byte[] protect(byte[] data) throws VaultException;

    byte[] unprotect(byte[] data) throws VaultException;

    /** The protector for this platform; empty where none is available (the key then stays in memory only). */
    static Optional<Protector> forPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? Optional.of(new DpapiProtector()) : Optional.empty();
    }
}
