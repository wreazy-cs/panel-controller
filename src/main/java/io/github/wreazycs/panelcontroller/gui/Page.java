package io.github.wreazycs.panelcontroller.gui;

import javafx.scene.Node;

/** One screen reached from the side menu. */
interface Page {
    String id();

    String title();

    String subtitle();

    String icon();

    Node view();

    /** Re-reads what the page shows (cheap; network and git work runs in the background). */
    void refresh();
}
