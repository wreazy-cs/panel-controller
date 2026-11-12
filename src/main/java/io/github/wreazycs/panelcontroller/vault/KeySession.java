package io.github.wreazycs.panelcontroller.vault;

import java.util.Arrays;
import java.util.Optional;

/**
 * Holds the API key in memory while the app runs. The value is never part of toString(), so it
 * cannot leak into logs by accident. {@link #clear()} overwrites the characters.
 */
public final class KeySession {
    private char[] key;
    private boolean remembered;

    public synchronized void set(char[] value, boolean rememberedOnDisk) {
        clear();
        key = value.clone();
        remembered = rememberedOnDisk;
    }

    public synchronized boolean hasKey() {
        return key != null;
    }

    /** True when the key is also stored in the vault (PIN protected). */
    public synchronized boolean isRemembered() {
        return key != null && remembered;
    }

    public synchronized void setRemembered(boolean value) {
        remembered = value;
    }

    /** A copy for the caller, who must wipe it after use. */
    public synchronized Optional<char[]> copy() {
        return key == null ? Optional.empty() : Optional.of(key.clone());
    }

    /** Only the last four characters, e.g. "••••••••3f9a". */
    public synchronized String masked() {
        if (key == null) {
            return "yok";
        }
        if (key.length < 8) {
            return "•".repeat(key.length);
        }
        return "•".repeat(8) + new String(key, key.length - 4, 4);
    }

    public synchronized void clear() {
        if (key != null) {
            Arrays.fill(key, '\0');
        }
        key = null;
        remembered = false;
    }

    @Override
    public String toString() {
        return "KeySession[" + (hasKey() ? "set" : "empty") + "]";
    }
}
