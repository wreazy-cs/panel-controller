package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the store's CDN address from the store's own address: the store page links its theme files
 * as {@code https://<cdn host>/<store no>/tema/<file>}, and that address with the file name replaced
 * by {@code %s} is what the rest of the app needs.
 *
 * <p>Only the page the person typed is requested, with a plain GET, and nothing is sent to it.
 */
public final class StoreFinder {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final Pattern THEME_LINK = Pattern.compile(
            "(?:https?:)?//([a-z0-9][a-z0-9.-]*\\.[a-z]{2,})/(\\d{1,10})/tema/[A-Za-z0-9_.-]+\\.(?:css|js)",
            Pattern.CASE_INSENSITIVE);

    /** What came back from the store page. */
    public enum Outcome {
        /** The address was found. */
        FOUND,
        /** The page loaded but links no theme files (a login page, an error page, another site). */
        NOT_FOUND,
        /** The site answered 401 or 403: closed to visitors (for example a B2B store). */
        CLOSED,
        /** Any other HTTP status. */
        HTTP_ERROR,
        /** No answer at all: wrong address, no internet, certificate problem. */
        UNREACHABLE,
        /** The text typed is not a usable address. */
        INVALID
    }

    /** {@code cdn} is set only for FOUND; {@code status} is the HTTP status when there was one. */
    public record Result(Outcome outcome, String cdn, int status, String siteUrl) {}

    private StoreFinder() {}

    /** "icelektronik.com.tr", "www.x.com/", "http://x.com/a" -> an https address, or empty when unusable. */
    public static Optional<URI> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String s = input.strip();
        if (s.isEmpty() || s.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        if (s.regionMatches(true, 0, "http://", 0, 7)) {
            s = s.substring(7);
        } else if (s.regionMatches(true, 0, "https://", 0, 8)) {
            s = s.substring(8);
        } else if (s.contains("://")) {
            return Optional.empty();
        }
        try {
            URI uri = new URI("https://" + s);
            String host = uri.getHost();
            if (host == null || !host.contains(".") || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * The CDN address template in a page, or empty. When the page links several (host, store no)
     * pairs, the most used one wins.
     */
    public static Optional<String> extract(String html) {
        if (html == null || html.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = THEME_LINK.matcher(html.replace("\\/", "/"));
        Map<String, Integer> count = new HashMap<>();
        String best = null;
        int bestCount = 0;
        while (m.find()) {
            String template = "https://" + m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2) + "/tema/%s";
            int c = count.merge(template, 1, Integer::sum);
            if (c > bestCount) {
                best = template;
                bestCount = c;
            }
        }
        return Optional.ofNullable(best);
    }

    public static Result find(String input) {
        Optional<URI> uri = normalize(input);
        if (uri.isEmpty()) {
            return new Result(Outcome.INVALID, null, 0, "");
        }
        return fetch(uri.get());
    }

    /** Package-private so tests can point it at a local server. */
    static Result fetch(URI uri) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // never from https down to http
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            String site = res.uri().toString();
            int status = res.statusCode();
            try (InputStream in = res.body()) {
                if (status == 401 || status == 403) {
                    return new Result(Outcome.CLOSED, null, status, site);
                }
                if (status != 200) {
                    return new Result(Outcome.HTTP_ERROR, null, status, site);
                }
                String html = new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
                Optional<String> cdn = extract(html);
                return cdn.isPresent() ? new Result(Outcome.FOUND, cdn.get(), status, site)
                        : new Result(Outcome.NOT_FOUND, null, status, site);
            }
        } catch (IOException | IllegalArgumentException e) {
            return new Result(Outcome.UNREACHABLE, null, 0, uri.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.UNREACHABLE, null, 0, uri.toString());
        }
    }
}
