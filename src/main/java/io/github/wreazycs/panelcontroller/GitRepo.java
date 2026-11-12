package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The project's git repository: changed files, commit of selected files, push.
 *
 * <p>Commit messages follow the project convention {@code 13.11.2026-Persembe-14.05 message -p}
 * ("-p": sent with Panel Controller) and carry a link to this app in the body. The author stays
 * the person's own git identity.
 */
public final class GitRepo {
    public static final String APP_LINK = "https://github.com/wreazy-cs/panel-controller";
    private static final String[] DAYS = {"Pazartesi", "Sali", "Carsamba", "Persembe", "Cuma", "Cumartesi", "Pazar"};
    private static final Pattern AHEAD = Pattern.compile("ahead (\\d+)");
    private static final Pattern BEHIND = Pattern.compile("behind (\\d+)");

    private final Path root;

    public GitRepo(Path root) {
        this.root = root;
    }

    /** One changed path as git reports it. */
    public record Change(String code, String path) {
        /** Turkish description of the two-letter status code. */
        public String label() {
            String c = code.trim();
            if (c.equals("??")) return "yeni (izlenmiyor)";
            if (c.contains("D")) return "silindi";
            if (c.contains("R")) return "yeniden adlandırıldı";
            if (c.contains("A")) return "eklendi";
            if (c.contains("U")) return "çakışma";
            return "değişti";
        }
    }

    public record Status(String branch, int ahead, int behind, List<Change> changes) {}

    /** Result of a git command. */
    public record Result(boolean ok, String output, Finding failure) {}

    public boolean isRepo() {
        try {
            return run(List.of("rev-parse", "--is-inside-work-tree"), 10).ok();
        } catch (IOException e) {
            return false;
        }
    }

    public Status status() throws IOException {
        Result r = run(List.of("-c", "core.quotepath=false", "status", "--porcelain=v1", "-z", "-b", "--untracked-files=all"), 20);
        if (!r.ok()) {
            throw new IOException(r.output());
        }
        return parseStatus(r.output());
    }

    static Status parseStatus(String z) {
        String[] parts = z.split("\0");
        String branch = "";
        int ahead = 0;
        int behind = 0;
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("## ")) {
                String head = p.substring(3);
                branch = head.split("\\.\\.\\.| ", 2)[0];
                Matcher a = AHEAD.matcher(head);
                if (a.find()) ahead = Integer.parseInt(a.group(1));
                Matcher b = BEHIND.matcher(head);
                if (b.find()) behind = Integer.parseInt(b.group(1));
                continue;
            }
            if (p.length() < 4) {
                continue;
            }
            String code = p.substring(0, 2);
            changes.add(new Change(code, p.substring(3)));
            if (code.charAt(0) == 'R' || code.charAt(0) == 'C') {
                i++; // -z puts the original name of a rename in the next field
            }
        }
        return new Status(branch, ahead, behind, changes);
    }

    /** "13.11.2026-Persembe-14.05 message -p" */
    public static String commitSubject(String message, LocalDateTime now) {
        return now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + "-" + DAYS[now.getDayOfWeek().getValue() - 1]
                + "-" + now.format(DateTimeFormatter.ofPattern("HH.mm")) + " " + message.strip() + " -p";
    }

    public static String commitBody() {
        return "Panel Controller ile gönderildi: " + APP_LINK;
    }

    /** Commits only the given paths (others that happen to be staged are left alone). */
    public Result commit(List<String> paths, String message) throws IOException {
        if (paths.isEmpty()) {
            return new Result(false, "", Finding.of(Problem.NOTHING_SELECTED, "", ""));
        }
        if (message == null || message.isBlank()) {
            return new Result(false, "", Finding.of(Problem.EMPTY_MESSAGE, "", ""));
        }
        List<String> add = new ArrayList<>(List.of("add", "--"));
        add.addAll(paths);
        Result added = run(add, 60);
        if (!added.ok()) {
            return new Result(false, added.output(), Finding.of(Problem.COMMIT_FAILED, "", firstLine(added.output())));
        }
        List<String> commit = new ArrayList<>(List.of("commit", "-m", commitSubject(message, LocalDateTime.now()),
                "-m", commitBody(), "--"));
        commit.addAll(paths);
        Result done = run(commit, 60);
        String log = added.output() + done.output();
        return done.ok() ? new Result(true, log, null)
                : new Result(false, log, Finding.of(Problem.COMMIT_FAILED, "", firstLine(done.output())));
    }

    public Result push() throws IOException {
        Result r = run(List.of("push"), 180);
        return r.ok() ? r : new Result(false, r.output(), Finding.of(Problem.PUSH_FAILED, "", firstLine(r.output())));
    }

    /** Last commits, one line each. */
    public List<String> log(int n) throws IOException {
        Result r = run(List.of("log", "-" + n, "--format=%h  %ad  %s", "--date=format:%d.%m %H:%M"), 20);
        return r.ok() ? r.output().lines().filter(l -> !l.isBlank()).toList() : List.of();
    }

    private static String firstLine(String s) {
        return s.lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("");
    }

    /** Runs git in the project; never waits for a password prompt. */
    Result run(List<String> args, int timeoutSeconds) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("LC_ALL", "C");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return new Result(false, e.getMessage(), Finding.of(Problem.GIT_MISSING, "", ""));
        }
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(false, "zaman asimi", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "kesildi", null);
        }
        return new Result(p.exitValue() == 0, new String(out, StandardCharsets.UTF_8), null);
    }

    public Path root() {
        return root;
    }

    public boolean hasGitDir() {
        return Files.exists(root.resolve(".git"));
    }
}
