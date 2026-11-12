package io.github.wreazycs.panelcontroller.vault;

/** Rules for the 4-digit PIN. */
public final class Pin {
    public static final int LENGTH = 4;

    private Pin() {}

    /** Exactly four ASCII digits. */
    public static boolean isWellFormed(char[] pin) {
        if (pin == null || pin.length != LENGTH) {
            return false;
        }
        for (char c : pin) {
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Why a new PIN is not acceptable, or null when it is. Rejects the most guessed PINs. */
    public static String problem(char[] pin) {
        if (!isWellFormed(pin)) {
            return "PIN 4 rakam olmalı.";
        }
        boolean same = true;
        boolean up = true;
        boolean down = true;
        for (int i = 1; i < pin.length; i++) {
            same &= pin[i] == pin[0];
            up &= pin[i] == pin[i - 1] + 1;
            down &= pin[i] == pin[i - 1] - 1;
        }
        if (same) {
            return "Dört aynı rakam (1111 gibi) olamaz.";
        }
        if (up || down) {
            return "Sıralı rakamlar (1234, 9876 gibi) olamaz.";
        }
        return null;
    }
}
