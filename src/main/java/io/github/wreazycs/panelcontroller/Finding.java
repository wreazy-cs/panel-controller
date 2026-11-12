package io.github.wreazycs.panelcontroller;

/**
 * One reported problem: what (code), where (file and position, may be empty) and the details.
 */
public record Finding(Problem problem, String where, String detail) {

    public static Finding of(Problem problem, String where, String detail) {
        return new Finding(problem, where == null ? "" : where, detail == null ? "" : detail);
    }

    /** "PC-101 genel.js:3:5  details" style line for the command line. */
    public String line() {
        StringBuilder sb = new StringBuilder(problem.code()).append(' ');
        if (!where.isEmpty() && !detail.startsWith(where)) {
            sb.append(where).append("  ");
        }
        sb.append(detail.isEmpty() ? problem.title() : detail);
        return sb.toString();
    }
}
