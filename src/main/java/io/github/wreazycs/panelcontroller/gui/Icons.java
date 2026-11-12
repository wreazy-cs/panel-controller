package io.github.wreazycs.panelcontroller.gui;

import java.util.Map;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/** Line icons drawn on a 24 x 24 grid (stroke only, coloured by CSS). */
final class Icons {
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("publish", "M12 15V4 M7 9l5-5 5 5 M5 15v4a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-4"),
            Map.entry("restore", "M3 12a9 9 0 1 0 2.6-6.4 M3 4v5h5 M12 8v4l3 2"),
            Map.entry("git", "M6 3v12 M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M18 9a9 9 0 0 1-9 9"),
            Map.entry("tasks", "M3 6l1.5 1.5L7 5 M3 12l1.5 1.5L7 11 M3 18l1.5 1.5L7 17 M11 6h10 M11 12h10 M11 18h10"),
            Map.entry("tools", "M9 3h6 M10 3v6L4.5 18.5A1.5 1.5 0 0 0 5.8 21h12.4a1.5 1.5 0 0 0 1.3-2.5L14 9V3 M7 15h10"),
            Map.entry("notes", "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M14 3v6h6 M8 13h8 M8 17h5"),
            Map.entry("about", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 16v-5 M12 8h.01"),
            Map.entry("settings", "M4 6h9 M17 6h3 M4 12h3 M11 12h9 M4 18h11 M19 18h1 M15 4v4 M9 10v4 M17 16v4"),
            Map.entry("guide", "M2 5h6a4 4 0 0 1 4 4v12a3 3 0 0 0-3-3H2z M22 5h-6a4 4 0 0 0-4 4v12a3 3 0 0 1 3-3h7z"),
            Map.entry("moon", "M20 13.5A8.5 8.5 0 1 1 10.5 4a6.5 6.5 0 0 0 9.5 9.5z"),
            Map.entry("sun", "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4"),
            Map.entry("refresh", "M20 12a8 8 0 1 1-2.3-5.7 M20 4v5h-5"),
            Map.entry("check", "M5 12.5l4.5 4.5L19 7.5"),
            Map.entry("x", "M6 6l12 12 M18 6L6 18"),
            Map.entry("alert", "M12 4l9 16H3z M12 10v4 M12 17h.01"),
            Map.entry("copy", "M9 9h10v11H9z M5 15H4V4h11v1"),
            Map.entry("play", "M7 4l13 8-13 8z"),
            Map.entry("compare", "M8 3v13 M4 12l4 4 4-4 M16 21V8 M12 12l4-4 4 4"),
            Map.entry("download", "M12 4v11 M7 10l5 5 5-5 M5 20h14"),
            Map.entry("folder", "M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"),
            Map.entry("external", "M14 4h6v6 M20 4l-9 9 M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"),
            Map.entry("key", "M15 8.5a4.5 4.5 0 1 0-4.4 5.4L4 20.5V22h3v-2h2v-2h2l1.6-1.6A4.5 4.5 0 0 0 15 8.5z M16.5 7.5h.01"),
            Map.entry("lock", "M6 11h12v10H6z M8 11V8a4 4 0 0 1 8 0v3"),
            Map.entry("panel", "M4 4h16v16H4z M4 9h16 M9 9v11"),
            Map.entry("search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z M20 20l-4-4"),
            Map.entry("upload", "M12 20V9 M7 14l5-5 5 5 M5 4h14"),
            Map.entry("server", "M4 4h16v6H4z M4 14h16v6H4z M8 7h.01 M8 17h.01"),
            Map.entry("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"),
            Map.entry("pen", "M4 20h4L19 9l-4-4L4 16z M14 6l4 4"),
            Map.entry("activity", "M3 12h4l3-8 4 16 3-8h4"),
            Map.entry("stop", "M6 6h12v12H6z"));

    private Icons() {}

    /** Icon of the given name at {@code size} pixels; extra style classes colour it (ok, warn, err, info, muted). */
    static Node of(String name, double size, String... styles) {
        SVGPath p = new SVGPath();
        p.setContent(PATHS.getOrDefault(name, PATHS.get("about")));
        p.getStyleClass().add("icon");
        p.getStyleClass().addAll(styles);
        double s = size / 24.0;
        p.setScaleX(s);
        p.setScaleY(s);
        Group g = new Group(p);
        g.setAutoSizeChildren(false);
        return new javafx.scene.layout.StackPane(g) {
            {
                setMinSize(size, size);
                setPrefSize(size, size);
                setMaxSize(size, size);
            }
        };
    }
}
