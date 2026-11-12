package io.github.wreazycs.panelcontroller;

/** A rule check failed; the build must stop. */
public class BuildException extends Exception {
    private final Finding finding;

    public BuildException(Problem problem, String where, String detail) {
        super(Finding.of(problem, where, detail).line());
        this.finding = Finding.of(problem, where, detail);
    }

    public Finding finding() {
        return finding;
    }
}
