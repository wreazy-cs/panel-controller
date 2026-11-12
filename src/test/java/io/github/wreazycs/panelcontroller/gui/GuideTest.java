package io.github.wreazycs.panelcontroller.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.wreazycs.panelcontroller.AppInfo;
import io.github.wreazycs.panelcontroller.Problem;
import org.junit.jupiter.api.Test;

class GuideTest {

    @Test
    void guideHasEveryErrorCodeAndNoPlaceholders() {
        String html = GuideTab.html();
        for (Problem p : Problem.values()) {
            assertTrue(html.contains("id=\"" + p.code() + "\""), "guide misses " + p.code());
        }
        assertFalse(html.contains("{{"), "unfilled placeholder");
        assertTrue(html.contains(AppInfo.VERSION));
    }

    @Test
    void everyGuideLinkPointsToSomething() {
        String html = GuideTab.html();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-go=\"([^\"]+)\"").matcher(html);
        while (m.find()) {
            assertTrue(html.contains("id=\"" + m.group(1) + "\""), "broken link to " + m.group(1));
        }
    }
}
