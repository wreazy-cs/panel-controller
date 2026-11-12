package io.github.wreazycs.panelcontroller;

import java.util.regex.Pattern;

/**
 * Removes comments from CSS and JS without touching strings, url(...) bodies or JS regex literals.
 * (Embedded base64 data can contain the sequence slash-star, so a naive remover corrupts files.)
 */
public final class CommentStripper {
    private static final String REGEX_PREV = "([{,;:=!&|?+-*%~^<>";
    private static final Pattern TRAILING_WS = Pattern.compile("[ \t]+\n");
    private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");

    private CommentStripper() {}

    public static String css(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(s, i, Math.min(j + 1, n));
                i = j + 1;
                continue;
            }
            if (s.startsWith("url(", i)) {
                int j = s.indexOf(')', i);
                j = j < 0 ? n : j + 1;
                out.append(s, i, j);
                i = j;
                continue;
            }
            if (s.startsWith("/*", i)) {
                int j = s.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static String js(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        char prev = 0; // last significant char of code (decides regex vs division)
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(s, i, Math.min(j + 1, n));
                i = j + 1;
                prev = c;
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char ns = s.charAt(i + 1);
                if (ns == '/') {
                    int j = s.indexOf('\n', i);
                    i = j < 0 ? n : j;
                    continue;
                }
                if (ns == '*') {
                    int j = s.indexOf("*/", i + 2);
                    i = j < 0 ? n : j + 2;
                    continue;
                }
                if (prev == 0 || REGEX_PREV.indexOf(prev) >= 0) {
                    int j = regexEnd(s, i);
                    if (j > 0) {
                        out.append(s, i, j + 1);
                        i = j + 1;
                        prev = '/';
                        continue;
                    }
                }
            }
            out.append(c);
            if (!Character.isWhitespace(c)) {
                prev = c;
            }
            i++;
        }
        return out.toString();
    }

    /** Index of the closing slash of a regex literal starting at {@code i}, or -1 if it is not one. */
    static int regexEnd(String s, int i) {
        int n = s.length();
        int j = i + 1;
        while (j < n && s.charAt(j) != '/') {
            if (s.charAt(j) == '\\') {
                j += 2;
                continue;
            }
            if (s.charAt(j) == '[') {
                while (j < n && s.charAt(j) != ']') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
            }
            if (j < n && s.charAt(j) == '\n') {
                break;
            }
            j++;
        }
        return (j < n && s.charAt(j) == '/') ? j : -1;
    }

    /** Collapses blank-line piles left behind after comments were removed. */
    public static String collapseBlankLines(String s) {
        s = TRAILING_WS.matcher(s).replaceAll("\n");
        return BLANK_RUN.matcher(s).replaceAll("\n\n");
    }
}
