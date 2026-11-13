package io.github.wreazycs.panelcontroller.gui;

import java.time.Duration;

/** "12 dk önce" / "12 min ago" in the chosen language. */
final class Ages {
    private Ages() {}

    static String describe(Duration d) {
        long min = Math.max(0, d.toMinutes());
        if (min < 1) return T.t("age.now");
        if (min < 60) return T.t("age.minutes", min);
        if (min < 48 * 60) return T.t("age.hours", min / 60);
        return T.t("age.days", min / 1440);
    }
}
