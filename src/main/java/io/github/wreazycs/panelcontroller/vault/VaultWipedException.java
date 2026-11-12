package io.github.wreazycs.panelcontroller.vault;

/** Too many wrong PINs: the vault file has been deleted. */
public class VaultWipedException extends VaultException {
    public VaultWipedException() {
        super(KeyVault.MAX_ATTEMPTS + " yanlış PIN girildi. Kayıtlı anahtar silindi.");
    }
}
