package io.github.wreazycs.panelcontroller;

/** A rule check failed; the build must stop. */
public class BuildException extends Exception {
    public BuildException(String message) {
        super(message);
    }
}
