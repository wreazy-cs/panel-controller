package io.github.wreazycs.panelcontroller.vault;

/** The vault could not be read or written. Messages never contain secret material. */
public class VaultException extends Exception {
    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
