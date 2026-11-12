package io.github.wreazycs.panelcontroller;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whitespace-only compression of the panel copy. Nothing that could change meaning is touched:
 * strings and url(...) bodies are kept verbatim.
 *
 * <ul>
 *   <li>CSS: every rule on one line, {@code selector{name:value;name:value}}</li>
 *   <li>JS: leading/trailing whitespace of each line removed, blank lines dropped</li>
 * </ul>
 */
public final class Minifier {

    private Minifier() {}

    // ------------------------------------------------------------------ parser

    enum Kind { RULE, GROUP, AT_BLOCK, AT_LINE }

    /** head = selector / at-rule prelude / full at-line text; body = declarations or raw block text. */
    record Node(Kind kind, String head, String body, List<Node> children) {}

    private static final List<String> GROUP_AT = List.of("@media", "@supports", "@layer", "@container", "@document");

    static int stringEnd(String s, int i) {
        char q = s.charAt(i);
        int j = i + 1;
        int n = s.length();
        while (j < n && s.charAt(j) != q) {
            j += s.charAt(j) == '\\' ? 2 : 1;
        }
        return j + 1;
    }

    /** s.startsWith("url(", i): returns the index just after the closing parenthesis. */
    static int urlEnd(String s, int i) {
        int j = i + 4;
        int n = s.length();
        while (j < n && " \t\r\n".indexOf(s.charAt(j)) >= 0) {
            j++;
        }
        if (j < n && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
            j = stringEnd(s, j);
        }
        int k = s.indexOf(')', j);
        return k < 0 ? n : k + 1;
    }

    /** s.charAt(i) == '{': returns the index just after the matching '}'. */
    static int blockEnd(String s, int i) {
        int depth = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = stringEnd(s, i);
                continue;
            }
            if (s.startsWith("url(", i)) {
                i = urlEnd(s, i);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        throw new IllegalArgumentException("CSS: kapanmayan blok");
    }

    static List<Node> parse(String s) {
        List<Node> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && " \t\r\n;}".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int j = i;
            while (j < n && s.charAt(j) != '{' && s.charAt(j) != ';') {
                char c = s.charAt(j);
                if (c == '"' || c == '\'') {
                    j = stringEnd(s, j);
                    continue;
                }
                if (s.startsWith("url(", j)) {
                    j = urlEnd(s, j);
                    continue;
                }
                j++;
            }
            String pre = s.substring(i, Math.min(j, n)).strip();
            if (j >= n) {
                if (!pre.isEmpty()) {
                    throw new IllegalArgumentException("CSS: bitmemis ifade: " + pre.substring(0, Math.min(60, pre.length())));
                }
                break;
            }
            if (s.charAt(j) == ';') {
                out.add(new Node(Kind.AT_LINE, pre, "", List.of()));
                i = j + 1;
                continue;
            }
            int k = blockEnd(s, j);
            String inner = s.substring(j + 1, k - 1);
            if (pre.startsWith("@")) {
                String name = pre.split("\\s+")[0].toLowerCase();
                if (GROUP_AT.contains(name)) { // list membership, not substring
                    out.add(new Node(Kind.GROUP, pre, "", parse(inner)));
                } else {
                    out.add(new Node(Kind.AT_BLOCK, pre, inner, List.of()));
                }
            } else {
                out.add(new Node(Kind.RULE, pre, inner, List.of()));
            }
            i = k;
        }
        return out;
    }

    // ------------------------------------------------------------- compression

    private static final Pattern KEEP = Pattern.compile(
            "url\\(\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\)|url\\(\\s*'(?:[^'\\\\]|\\\\.)*'\\s*\\)|url\\([^)]*\\)"
                    + "|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("\u0000(\\d+)\u0000");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern IMPORTANT_TAIL = Pattern.compile("\\s*!\\s*important\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPORTANT = Pattern.compile("\\s*!\\s*important");
    private static final Pattern COMMA = Pattern.compile("\\s*,\\s*");
    private static final Pattern COMBINATOR = Pattern.compile("\\s*([>~+])\\s*");
    private static final Pattern OPEN_PAREN = Pattern.compile("\\(\\s+");
    private static final Pattern CLOSE_PAREN = Pattern.compile("\\s+\\)");
    private static final Pattern COLON = Pattern.compile("\\s*:\\s*");

    /** Masks strings and url(...) so whitespace rules cannot touch them. */
    private static String mask(String s, List<String> saved) {
        Matcher m = KEEP.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            saved.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement("\u0000" + (saved.size() - 1) + "\u0000"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String unmask(String s, List<String> saved) {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(saved.get(Integer.parseInt(m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String selector(String sel) {
        List<String> saved = new ArrayList<>();
        String s = mask(sel, saved);
        s = WS.matcher(s).replaceAll(" ").strip();
        s = COMMA.matcher(s).replaceAll(",");
        s = COMBINATOR.matcher(s).replaceAll("$1");
        return unmask(s, saved);
    }

    static String declarations(String body) {
        List<String> saved = new ArrayList<>();
        String s = mask(body, saved);
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ';' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());

        List<String> out = new ArrayList<>();
        for (String raw : parts) {
            String b = raw.strip();
            if (b.isEmpty()) {
                continue;
            }
            int colon = b.indexOf(':');
            if (colon < 0) {
                out.add(WS.matcher(b).replaceAll(" "));
                continue;
            }
            String name = b.substring(0, colon).strip();
            String value = b.substring(colon + 1);
            String lower = value.toLowerCase();
            if (name.startsWith("--") || lower.contains("var(") || lower.contains("env(")) {
                // Measured in Chrome: custom properties and var()/env() values are stored verbatim,
                // so their inner whitespace must stay. Only the ends and !important are normalised.
                Matcher m = IMPORTANT_TAIL.matcher(value);
                if (m.find()) {
                    out.add(name + ":" + value.substring(0, m.start()).strip() + "!important");
                } else {
                    out.add(name + ":" + value.strip());
                }
                continue;
            }
            value = WS.matcher(value).replaceAll(" ").strip();
            value = IMPORTANT.matcher(value).replaceAll("!important");
            value = COMMA.matcher(value).replaceAll(",");
            value = OPEN_PAREN.matcher(value).replaceAll("(");
            value = CLOSE_PAREN.matcher(value).replaceAll(")");
            out.add(name + ":" + value);
        }
        return unmask(String.join(";", out), saved);
    }

    static String atPrelude(String pre) {
        List<String> saved = new ArrayList<>();
        String s = mask(pre, saved);
        s = WS.matcher(s).replaceAll(" ").strip();
        s = OPEN_PAREN.matcher(s).replaceAll("(");
        s = CLOSE_PAREN.matcher(s).replaceAll(")");
        s = COLON.matcher(s).replaceAll(":");
        s = COMMA.matcher(s).replaceAll(",");
        return unmask(s, saved);
    }

    private static boolean isKeyframes(Node x) {
        return x.head().split("\\s+")[0].toLowerCase().endsWith("keyframes");
    }

    private static String keyframeBody(String raw) {
        StringBuilder sb = new StringBuilder();
        for (Node k : parse(raw)) {
            if (k.kind() == Kind.RULE) {
                sb.append(selector(k.head())).append('{').append(declarations(k.body())).append('}');
            }
        }
        return sb.toString();
    }

    private static String write(List<Node> nodes) {
        List<String> lines = new ArrayList<>();
        for (Node x : nodes) {
            switch (x.kind()) {
                case RULE -> lines.add(selector(x.head()) + "{" + declarations(x.body()) + "}");
                case GROUP -> lines.add(atPrelude(x.head()) + "{\n" + write(x.children()) + "\n}");
                case AT_BLOCK -> {
                    if (isKeyframes(x)) {
                        lines.add(atPrelude(x.head()) + "{" + keyframeBody(x.body()) + "}");
                    } else {
                        lines.add(atPrelude(x.head()) + "{" + declarations(x.body()) + "}");
                    }
                }
                case AT_LINE -> lines.add(atPrelude(x.head()) + ";");
            }
        }
        return String.join("\n", lines);
    }

    /** Comment-free CSS to one-rule-per-line CSS. */
    public static String css(String text) {
        return write(parse(text));
    }

    /** Comment-free JS to indentation-free JS without blank lines. */
    public static String js(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------- checks

    /** Whitespace-independent summary of a stylesheet's rules, used to prove compression changed nothing. */
    public static List<String> cssSummary(String text) {
        List<String> out = new ArrayList<>();
        summarize(parse(text), "", out);
        return out;
    }

    private static void summarize(List<Node> nodes, String context, List<String> out) {
        for (Node x : nodes) {
            switch (x.kind()) {
                case GROUP -> summarize(x.children(), context + "@" + atPrelude(x.head()) + ">", out);
                case RULE -> out.add(context + "|" + selector(x.head()) + "|" + declarations(x.body()));
                case AT_BLOCK -> {
                    if (isKeyframes(x)) {
                        out.add(context + "|" + atPrelude(x.head()) + "|" + keyframeBody(x.body()));
                    } else {
                        out.add(context + "|" + atPrelude(x.head()) + "|" + declarations(x.body()));
                    }
                }
                case AT_LINE -> out.add(context + "|" + atPrelude(x.head()) + "|");
            }
        }
    }

    /**
     * Returns a description if the JS contains a multi-line string or a backtick (template literal),
     * which makes line-by-line whitespace stripping unsafe; otherwise null.
     */
    public static String jsLineProblem(String text) {
        int i = 0;
        int n = text.length();
        char prev = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '`') {
                return "ters tirnak (template literal) @" + i;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c) {
                    if (text.charAt(j) == '\n') {
                        return "satira yayilan dizgi @" + i;
                    }
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
                prev = c;
                continue;
            }
            if (c == '/' && (prev == 0 || "([{,;:=!&|?+-*%~^<>".indexOf(prev) >= 0)) {
                int j = CommentStripper.regexEnd(text, i);
                if (j > 0) {
                    i = j + 1;
                    prev = '/';
                    continue;
                }
            }
            if (!Character.isWhitespace(c)) {
                prev = c;
            }
            i++;
        }
        return null;
    }
}
