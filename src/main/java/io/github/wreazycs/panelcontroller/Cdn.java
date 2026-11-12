package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the live theme files from the store's CDN.
 *
 * <p>Two lessons from the Python tools: the CDN rejects the default Java user agent with 403, so a
 * browser user agent is sent; and Cloudflare caches these files for a year, so every request gets a
 * unique query string to see what is really live.
 */
public final class Cdn {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    static final Charset PANEL_CHARSET = Charset.forName("windows-1254");

    private final String template;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** @param template URL with one %s for the file name, e.g. https://cdn.example.com/STORE_ID/tema/%s */
    public Cdn(String template) {
        if (template == null || !template.contains("%s")) {
            throw new IllegalArgumentException("'cdn' ayari yok ya da %s icermiyor (panel-controller.properties)");
        }
        this.template = template;
    }

    /** Live content of the file, normalised to \n line endings; empty if the request failed. */
    public Optional<String> fetch(String fileName) {
        try {
            URI uri = URI.create(template.formatted(fileName) + "?cb=" + UUID.randomUUID().toString().replace("-", ""));
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(new String(res.body(), PANEL_CHARSET).replace("\r\n", "\n"));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
