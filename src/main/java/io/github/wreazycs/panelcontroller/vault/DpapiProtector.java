package io.github.wreazycs.panelcontroller.vault;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.nio.charset.StandardCharsets;

/**
 * Windows DPAPI (CryptProtectData) through JNA. Bound to the current Windows user; a copy of the
 * file on another account or computer cannot be opened. The app-specific entropy means other
 * programs that call DPAPI without it cannot unprotect the data either.
 */
final class DpapiProtector implements Protector {
    private static final byte[] ENTROPY =
            "io.github.wreazycs.panel-controller/vault/v1".getBytes(StandardCharsets.US_ASCII);

    @Override
    public byte[] protect(byte[] data) throws VaultException {
        try {
            return Crypt32Util.cryptProtectData(data, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Panel Controller", null);
        } catch (RuntimeException | LinkageError e) {
            throw new VaultException("Windows DPAPI ile şifrelenemedi.", e);
        }
    }

    @Override
    public byte[] unprotect(byte[] data) throws VaultException {
        try {
            return Crypt32Util.cryptUnprotectData(data, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
        } catch (RuntimeException | LinkageError e) {
            throw new VaultException("Kasa bu Windows hesabında açılamıyor (başka hesaptan ya da bilgisayardan kopyalanmış olabilir).", e);
        }
    }
}
