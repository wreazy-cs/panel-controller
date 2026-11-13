package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the app needs from the computer, checked without changing anything. Java comes with the
 * installer; git, Node.js and Python are optional and each enables one feature. Missing ones can be
 * installed with winget (the person sees and accepts its prompts). Texts are added by the interface
 * from {@link Item#id()}.
 */
public final class SystemCheck {
    /** "git version 2.55.0", "v24.19.0", "Python 3.14.7", "java 27 2026-09-15": the first number, minor optional. */
    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private SystemCheck() {}

    public enum State { OK, MISSING, OLD, PROBLEM }

    /**
     * One check. {@code id}: java, git, node, python, scripts or winget. {@code version} is what was found,
     * {@code minimum} what is needed (for OLD), {@code info} extra detail (script count, failing file,
     * bundled flag "bundled"). {@code wingetId} is the package to install when missing, or null.
     */
    public record Item(String id, String name, State state, String version, String minimum, String info, String wingetId) {
        public boolean ok() {
            return state == State.OK;
        }
    }

    /** Runs all checks; {@code projectRoot} may be null. */
    public static List<Item> run(Path projectRoot) {
        List<Item> items = new ArrayList<>();
        items.add(java());
        items.add(tool("git", "Git", List.of("git", "--version"), 2, 30, "Git.Git"));
        items.add(tool("node", "Node.js", List.of(nodeCommand(), "--version"), 18, 0, "OpenJS.NodeJS.LTS"));
        Item python = tool("python", "Python", List.of(pythonCommand(), "--version"), 3, 8, "Python.Python.3.13");
        items.add(python);
        if (projectRoot != null) {
            items.add(projectScripts(projectRoot, python.ok()));
        }
        items.add(winget());
        return items;
    }

    static Item java() {
        String v = System.getProperty("java.version", "?");
        Path home = Path.of(System.getProperty("java.home", "."));
        Path parent = home.getParent() == null ? home : home.getParent();
        boolean bundled = Files.isRegularFile(parent.resolve("app").resolve("Panel Controller.cfg"));
        if (Runtime.version().feature() < 25) {
            return new Item("java", "Java", State.OLD, v, "25", "", "EclipseAdoptium.Temurin.25.JRE");
        }
        return new Item("java", "Java", State.OK, v, "", bundled ? "bundled" : "", null);
    }

    /** Runs {@code cmd} and reads the first version number from its output. */
    static Item tool(String id, String name, List<String> cmd, int minMajor, int minMinor, String wingetId) {
        String out = runQuiet(cmd, 15);
        String min = minMajor + "." + minMinor;
        if (out == null) {
            return new Item(id, name, State.MISSING, "", min, "", wingetId);
        }
        Matcher m = VERSION.matcher(out);
        if (!m.find()) {
            return new Item(id, name, State.PROBLEM, "", min, firstLine(out), wingetId);
        }
        int major = Integer.parseInt(m.group(1));
        int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (major < minMajor || (major == minMajor && minor < minMinor)) {
            return new Item(id, name, State.OLD, m.group(0), min, "", wingetId);
        }
        return new Item(id, name, State.OK, m.group(0), min, "", null);
    }

    /** Parses every arac/*.py and kumanda/*.py of the project with Python (nothing is run or written). */
    static Item projectScripts(Path root, boolean pythonOk) {
        List<Path> scripts = new ArrayList<>();
        for (String dir : new String[] {"arac", "kumanda"}) {
            Path d = root.resolve(dir);
            if (Files.isDirectory(d)) {
                try (Stream<Path> s = Files.list(d)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".py")).sorted().forEach(scripts::add);
                } catch (IOException e) {
                    return new Item("scripts", "Python", State.PROBLEM, "", "", e.getMessage(), null);
                }
            }
        }
        String count = String.valueOf(scripts.size());
        if (scripts.isEmpty()) {
            return new Item("scripts", "Python", State.OK, "", "", "0", null);
        }
        if (!pythonOk) {
            return new Item("scripts", "Python", State.MISSING, "", "", count, null);
        }
        List<String> cmd = new ArrayList<>(List.of(pythonCommand(), "-I", "-c",
                "import ast,sys\nbad=[]\nfor f in sys.argv[1:]:\n"
                        + "    try: ast.parse(open(f,encoding='utf-8').read(),f)\n"
                        + "    except SyntaxError as e: bad.append('%s:%s %s'%(f,e.lineno,e.msg))\n"
                        + "print('OK' if not bad else 'BAD '+' | '.join(bad))"));
        scripts.forEach(p -> cmd.add(p.toString()));
        String out = runQuiet(cmd, 60);
        if (out == null) {
            return new Item("scripts", "Python", State.PROBLEM, "", "", "python -c", null);
        }
        if (out.strip().startsWith("OK")) {
            return new Item("scripts", "Python", State.OK, "", "", count, null);
        }
        return new Item("scripts", "Python", State.PROBLEM, "", "",
                out.strip().replace(root.toString() + java.io.File.separator, ""), null);
    }

    static Item winget() {
        String out = runQuiet(List.of("winget", "--version"), 15);
        return out == null
                ? new Item("winget", "winget", State.MISSING, "", "", "", null)
                : new Item("winget", "winget", State.OK, firstLine(out), "", "", null);
    }

    static String pythonCommand() {
        return runQuiet(List.of("python", "--version"), 10) != null ? "python" : "py";
    }

    static String nodeCommand() {
        if (runQuiet(List.of("node", "--version"), 10) != null) {
            return "node";
        }
        Path p = Path.of("C:\\Program Files\\nodejs\\node.exe");
        return Files.isRegularFile(p) ? p.toString() : "node";
    }

    /** The winget command that installs a package, in a visible console so its prompts are seen. */
    public static List<String> installCommand(String wingetId) {
        if (wingetId == null || !wingetId.matches("[A-Za-z0-9.\\-]+")) {
            throw new IllegalArgumentException("invalid package id");
        }
        // start "" <command>: the empty first argument is the window title
        return List.of("cmd.exe", "/c", "start", "", "cmd.exe", "/k",
                "winget", "install", "--id", wingetId, "-e", "--source", "winget");
    }

    /** Output of a command (stdout + stderr), or null when it cannot be run or fails. */
    static String runQuiet(List<String> cmd, int timeoutSeconds) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            // the Microsoft Store "python" stub fails with exit code 9009
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String firstLine(String s) {
        return s.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
    }
}
