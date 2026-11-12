package io.github.wreazycs.panelcontroller.vault;

/** The PIN did not open the vault. The failed attempt has been counted. */
public class WrongPinException extends VaultException {
    private final int remaining;

    public WrongPinException(int remaining) {
        super("Yanlış PIN. Kalan deneme: " + remaining);
        this.remaining = remaining;
    }

    /** Attempts left before the stored key is deleted. */
    public int remaining() {
        return remaining;
    }
}
