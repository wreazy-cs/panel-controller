package io.github.wreazycs.panelcontroller;

/** One panel slot: source folder, output file name, and the slot's name in the admin panel. */
public record Slot(String folder, String outputName, String panelName) {
    public boolean isJs() {
        return outputName.endsWith(".js");
    }
}
