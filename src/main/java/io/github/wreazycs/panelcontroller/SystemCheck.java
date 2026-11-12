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
 * "Sistem kontrolü": what the app needs from the computer, checked without changing anything.
 * Java comes with the installer; git, Node.js and Python are optional helpers that unlock a
 * feature each. Missing ones can be installed with winget (the person sees and accepts its prompts).
 */
public final class SystemCheck {
    /** "git version 2.55.0", "v24.19.0", "Python 3.14.7", "java 27 2026-09-15": the first number, minor optional. */
    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private SystemCheck() {}

    public enum State { OK, MISSING, OLD, PROBLEM }

    /**
     * One check. {@code wingetId} is the package to install when missing (null: nothing to install).
     * {@code needed} says what stops working without it.
     */
    public record Item(String name, String needed, State state, String detail, String wingetId) {
        public boolean ok() {
            return state == State.OK;
        }
    }

    /** Runs all checks; {@code projectRoot} may be null. */
    public static List<Item> run(Path projectRoot) {
        List<Item> items = new ArrayList<>();
        items.add(java());
        items.add(tool("Git", "Git sayfası (commit, push) ve durum kartı", List.of("git", "--version"), 2, 30, "Git.Git"));
        items.add(tool("Node.js", "Doğrulamadaki JS sözdizimi denetimi (PC-208 uyarısı)",
                List.of(nodeCommand(), "--version"), 18, 0, "OpenJS.NodeJS.LTS"));
        Item python = tool("Python", "Test tezgahı ve projenin kendi Python betikleri", List.of(pythonCommand(), "--version"),
                3, 8, "Python.Python.3.13");
        items.add(python);
        if (projectRoot != null) {
            items.add(projectScripts(projectRoot, python.ok()));
        }
        items.add(winget());
        return items;
    }

    static Item java() {
        String v = System.getProperty("java.version", "?");
        String home = System.getProperty("java.home", "");
        boolean bundled = Files.isRegularFile(Path.of(home).getParent() == null ? Path.of(home) : Path.of(home).getParent()
                .resolve("app").resolve("Panel Controller.cfg"));
        int major = Runtime.version().feature();
        if (major < 25) {
            return new Item("Java", "Uygulamanın kendisi", State.OLD, "Java " + v + " (en az 25 gerekir)", "EclipseAdoptium.Temurin.25.JRE");
        }
        return new Item("Java", "Uygulamanın kendisi", State.OK,
                "Java " + v + (bundled ? " · uygulamanın içinde" : ""), null);
    }

    /** Runs {@code cmd} and reads the first version number from its output. */
    static Item tool(String name, String needed, List<String> cmd, int minMajor, int minMinor, String wingetId) {
        String out = runQuiet(cmd, 15);
        if (out == null) {
            return new Item(name, needed, State.MISSING, "bulunamadı", wingetId);
        }
        Matcher m = VERSION.matcher(out);
        if (!m.find()) {
            return new Item(name, needed, State.PROBLEM, "sürüm okunamadı: " + firstLine(out), wingetId);
        }
        int major = Integer.parseInt(m.group(1));
        int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        String version = m.group(0);
        if (major < minMajor || (major == minMajor && minor < minMinor)) {
            return new Item(name, needed, State.OLD, version + " (en az " + minMajor + "." + minMinor + ")", wingetId);
        }
        return new Item(name, needed, State.OK, version, null);
    }

    /** Parses every arac/*.py and kumanda/*.py of the project with Python (nothing is run or written). */
    static Item projectScripts(Path root, boolean pythonOk) {
        String needed = "Projenin Python araçları (arac/, kumanda/)";
        List<Path> scripts = new ArrayList<>();
        for (String dir : new String[] {"arac", "kumanda"}) {
            Path d = root.resolve(dir);
            if (Files.isDirectory(d)) {
                try (Stream<Path> s = Files.list(d)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".py")).sorted().forEach(scripts::add);
                } catch (IOException e) {
                    return new Item("Python betikleri", needed, State.PROBLEM, e.getMessage(), null);
                }
            }
        }
        if (scripts.isEmpty()) {
            return new Item("Python betikleri", needed, State.OK, "bu projede Python betiği yok", null);
        }
        if (!pythonOk) {
            return new Item("Python betikleri", needed, State.MISSING, scripts.size() + " betik var, Python olmadan denetlenemiyor", null);
        }
        List<String> cmd = new ArrayList<>(List.of(pythonCommand(), "-I", "-c",
                "import ast,sys\nbad=[]\nfor f in sys.argv[1:]:\n"
                        + "    try: ast.parse(open(f,encoding='utf-8').read(),f)\n"
                        + "    except SyntaxError as e: bad.append('%s:%s %s'%(f,e.lineno,e.msg))\n"
                        + "print('OK' if not bad else 'BAD '+' | '.join(bad))"));
        scripts.forEach(p -> cmd.add(p.toString()));
        String out = runQuiet(cmd, 60);
        if (out == null) {
            return new Item("Python betikleri", needed, State.PROBLEM, "denetim çalışmadı", null);
        }
        if (out.strip().startsWith("OK")) {
            return new Item("Python betikleri", needed, State.OK, scripts.size() + " betik sözdizimi temiz", null);
        }
        return new Item("Python betikleri", needed, State.PROBLEM,
                out.strip().replace(root.toString() + java.io.File.separator, ""), null);
    }

    static Item winget() {
        String out = runQuiet(List.of("winget", "--version"), 15);
        return out == null
                ? new Item("winget", "Eksik araçları tek tıkla kurmak", State.MISSING,
                        "yok: araçları kendi sitelerinden kur (git-scm.com, nodejs.org, python.org)", null)
                : new Item("winget", "Eksik araçları tek tıkla kurmak", State.OK, firstLine(out), null);
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

    /** The winget command that installs a package, run in a visible console so its prompts are seen. */
    public static List<String> installCommand(String wingetId) {
        if (wingetId == null || !wingetId.matches("[A-Za-z0-9.\\-]+")) {
            throw new IllegalArgumentException("geçersiz paket");
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
            String text = new String(out, StandardCharsets.UTF_8);
            // the Microsoft Store "python" stub exits with 9009 and prints a hint
            return p.exitValue() == 0 ? text : null;
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
