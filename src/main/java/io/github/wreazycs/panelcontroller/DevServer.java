package io.github.wreazycs.panelcontroller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local test bench: serves the project folder on 127.0.0.1 with CORS and no caching, so a test page
 * (or the live site, through the browser) can load the source files directly.
 *
 * <p>Only this computer can connect (loopback). .git, backups and the generated API files are never
 * served, and paths cannot leave the project folder.
 */
public final class DevServer {
    public static final int PORT = 8732;
    private static final List<String> BLOCKED = List.of(".git", "veri/yedek", ".agents", ".idea");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml", "png", "image/png", "jpg", "image/jpeg", "webp", "image/webp",
            "woff2", "font/woff2", "md", "text/plain; charset=utf-8");

    private final Path root;
    private HttpServer server;

    public DevServer(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public synchronized boolean running() {
        return server != null;
    }

    public synchronized void start(int port) throws IOException {
        if (server != null) {
            return;
        }
        HttpServer s;
        try {
            s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (BindException e) {
            throw new BindException("port " + port + " kullanımda");
        }
        s.createContext("/", this::handle);
        s.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dev-server");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        server = s;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public String url(String relative) {
        return "http://127.0.0.1:" + (server == null ? PORT : server.getAddress().getPort()) + "/" + relative.replace('\\', '/');
    }

    /** The file a request path maps to, or null when it is outside the project or blocked. */
    Path resolve(String rawPath) {
        String path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root)) {
            return null;
        }
        String rel = root.relativize(p).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String b : BLOCKED) {
            if (rel.equals(b) || rel.startsWith(b + "/")) {
                return null;
            }
        }
        return p;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Private-Network", "true");
            ex.getResponseHeaders().add("Cache-Control", "no-store");
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) {
                ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                ex.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            URI uri = ex.getRequestURI();
            Path file = resolve(uri.getRawPath());
            if (file == null || !Files.isRegularFile(file)) {
                byte[] msg = "bulunamadi".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, msg.length);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(msg);
                }
                return;
            }
            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            ex.getResponseHeaders().add("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
            byte[] body = Files.readAllBytes(file);
            if (method.equals("HEAD")) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        }
    }

    // --------------------------------------------------------- project script

    /** Path of the project's own bench generator, if it has one. */
    public static Path benchScript(Path root) {
        return root.resolve("arac").resolve("tezgah-yap.py");
    }

    public static Path benchPage(Path root) {
        return root.resolve("veri").resolve("uretilen").resolve("tezgah.html");
    }

    /** Runs the project's bench generator with Python. Returns its output; throws with a Problem-coded message. */
    public static String generateBench(Path root) throws IOException, BuildException {
        Path script = benchScript(root);
        if (!Files.isRegularFile(script)) {
            throw new BuildException(Problem.BENCH_SCRIPT_MISSING, "arac/tezgah-yap.py", "");
        }
        Path page = benchPage(root);
        Files.createDirectories(page.getParent());
        for (String python : new String[] {"python", "py"}) {
            Process p;
            try {
                p = new ProcessBuilder(python, script.toString(), root.toString(), page.toString())
                        .directory(root.toFile()).redirectErrorStream(true).start();
            } catch (IOException e) {
                continue;
            }
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("tezgah betiği zaman aşımına uğradı");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("kesildi");
            }
            if (p.exitValue() != 0) {
                throw new IOException("tezgah betiği hata verdi: " + out.strip());
            }
            return out.strip();
        }
        throw new BuildException(Problem.PYTHON_MISSING, "", "");
    }
}
