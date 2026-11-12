package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only API probe (GET only) for the PlatinMarket "Reform" API
 * (https://developer.platinmarket.com/reform/&lt;controller&gt;/&lt;action&gt;.json?access_token=...).
 * The Reform API has no endpoints for theme CSS/JS slots, so uploads stay manual. This class makes
 * the call safe:
 *
 * <ul>
 *   <li>https only (plain http is refused, except a local test server in tests)</li>
 *   <li>redirects are never followed, so the key cannot be carried to another server</li>
 *   <li>the key is masked everywhere in the result: plain, URL-encoded, base64 and hex</li>
 *   <li>at most 200 KB of the body is read</li>
 * </ul>
 *
 * There is deliberately no command line path to this class.
 */
public final class ApiProbe {
    public static final int MAX_BODY = 200 * 1024;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final List<String> SHOWN_HEADERS = List.of("content-type", "server", "location", "www-authenticate",
            "allow", "x-ratelimit-limit", "x-ratelimit-remaining", "retry-after");

    private ApiProbe() {}

    public enum Auth {
        HEADER("Başlıkta"), QUERY("Sorgu parametresinde"), NONE("Anahtarsız");

        public final String label;

        Auth(String label) {
            this.label = label;
        }
    }

    /** What to call. {@code name} is the header or query parameter name, {@code prefix} e.g. "Bearer ". */
    public record Request(String base, String path, Auth auth, String name, String prefix) {}

    /** The result; every text field is already masked. {@code problem} is null for a 2xx answer. */
    public record Result(int status, String url, Map<String, String> headers, String body, long size,
                         boolean truncated, String meaning, Finding problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    /** The full URL for a request, or an exception explaining why it cannot be built. */
    static URI target(Request r, char[] key, boolean allowLocalHttp) {
        URI base;
        try {
            base = URI.create(r.base().strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Adres anlaşılamadı.");
        }
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        boolean local = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
        if (!scheme.equals("https") && !(allowLocalHttp && scheme.equals("http") && local)) {
            throw new IllegalArgumentException("Yalnızca https:// adreslerine istek atılır.");
        }
        if (base.getHost() == null || base.getHost().isBlank()) {
            throw new IllegalArgumentException("Adreste sunucu adı yok.");
        }
        String path = r.path() == null || r.path().isBlank() ? "/" : r.path().strip();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String url = scheme + "://" + base.getRawAuthority() + path;
        if (r.auth() == Auth.QUERY) {
            if (r.name() == null || r.name().isBlank()) {
                throw new IllegalArgumentException("Sorgu parametresinin adı boş.");
            }
            url += (url.contains("?") ? "&" : "?") + URLEncoder.encode(r.name().strip(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(new String(key), StandardCharsets.UTF_8);
        }
        return URI.create(url);
    }

    /** Sends the GET request. {@code key} may be empty when auth is NONE. */
    public static Result get(Request r, char[] key) throws IOException, InterruptedException {
        return get(r, key, false);
    }

    static Result get(Request r, char[] key, boolean allowLocalHttp) throws IOException, InterruptedException {
        if (r.auth() != Auth.NONE && (key == null || key.length == 0)) {
            return failed(r, Problem.API_NO_KEY, "");
        }
        char[] k = key == null ? new char[0] : key;
        URI uri;
        try {
            uri = target(r, k, allowLocalHttp);
        } catch (IllegalArgumentException e) {
            return failed(r, Problem.API_HTTPS_ONLY, e.getMessage());
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA).header("Accept", "application/json, text/plain, */*");
        if (r.auth() == Auth.HEADER) {
            if (r.name() == null || r.name().isBlank()) {
                return failed(r, Problem.API_HTTPS_ONLY, "Başlık adı boş.");
            }
            b.header(r.name().strip(), (r.prefix() == null ? "" : r.prefix()) + new String(k));
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15)).build();
        HttpResponse<InputStream> res;
        try {
            res = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            return new Result(0, mask(uri.toString(), k), Map.of(), "", 0, false,
                    "Bağlantı kurulamadı.", Finding.of(Problem.API_NETWORK, "", mask(String.valueOf(e.getMessage()), k)));
        }
        byte[] body;
        boolean truncated;
        try (InputStream in = res.body()) {
            body = in.readNBytes(MAX_BODY);
            truncated = in.read() != -1;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        res.headers().map().forEach((name, values) -> {
            if (SHOWN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, mask(String.join(", ", values), k));
            }
        });
        int status = res.statusCode();
        String text = mask(decode(body), k);
        Problem p = problemFor(status);
        if (p == null) {
            // Reform API: errors come back as HTTP 200 with response.error.code set
            Matcher m = ENVELOPE_ERROR.matcher(text);
            if (m.find()) {
                String code = m.group(1);
                String message = m.group(2) == null ? "" : m.group(2);
                return new Result(status, mask(uri.toString(), k), headers, text, body.length, truncated,
                        "API hata döndürdü: " + code + " " + message + " (" + envelopeMeaning(code) + ")",
                        Finding.of(Problem.API_REJECTED, "error.code " + code, message));
            }
        }
        return new Result(status, mask(uri.toString(), k), headers, text, body.length, truncated,
                meaning(status), p == null ? null : Finding.of(p, "HTTP " + status, ""));
    }

    /** {"error":{"code":101,"message":"Unauthorized" ...}}; a null code means no error. */
    private static final Pattern ENVELOPE_ERROR = Pattern.compile(
            "\"error\"\\s*:\\s*\\{\\s*\"code\"\\s*:\\s*\"?(\\d+)\"?\\s*,\\s*\"message\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|null)");

    static String envelopeMeaning(String code) {
        return switch (code) {
            case "100" -> "anahtar gönderilmedi";
            case "101" -> "yetkisiz ya da geçersiz anahtar";
            case "102" -> "anahtarın süresi dolmuş";
            case "103" -> "müşteri bulunamadı";
            case "104" -> "veritabanı bağlantı hatası";
            case "300" -> "işlem hatası, örneğin yalnızca POST";
            case "400" -> "eksik ya da geçersiz parametre";
            case "404" -> "kayıt bulunamadı";
            default -> "belgede yok";
        };
    }

    /** Ready-made read-only Reform API calls. Endpoints that return personal data are left out on purpose. */
    public record Preset(String label, String path) {}

    public static final String REFORM_BASE = "https://developer.platinmarket.com";

    public static final List<Preset> PRESETS = List.of(
            new Preset("Mağaza bilgisi (anahtar hangi mağazanın)", "/reform/products/info.json"),
            new Preset("Döviz kurları", "/reform/exchange_rates/index.json"),
            new Preset("Markalar", "/reform/brands/index.json"),
            new Preset("Kategoriler", "/reform/categories/index.json"),
            new Preset("Kargo seçenekleri", "/reform/shippings/index.json"),
            new Preset("Özellik grupları", "/reform/features/index.json"),
            new Preset("Varyasyon grupları", "/reform/variationGroups/index.json"));

    private static Result failed(Request r, Problem p, String detail) {
        return new Result(0, "", Map.of(), "", 0, false, p.title(), Finding.of(p, "", detail));
    }

    /** What a status code probably means for an API call (a guess, not documentation). */
    public static String meaning(int status) {
        if (status >= 200 && status < 300) return "Başarılı: bu yol okunabiliyor.";
        if (status >= 300 && status < 400) return "Yönlendirme (izlenmedi). Location başlığındaki adres doğru yol olabilir.";
        return switch (status) {
            case 401 -> "Kimlik doğrulanamadı: anahtar yanlış ya da gönderme biçimi (başlık/sorgu/önek) farklı.";
            case 403 -> "Yasak: anahtarın bu yola yetkisi yok ya da isteğin geldiği IP adresi anahtarda tanımlı değil.";
            case 404 -> "Yol bulunamadı: adres ya da yol farklı olabilir.";
            case 405 -> "Bu yol GET'i desteklemiyor (yalnızca yazma olabilir).";
            case 429 -> "Çok fazla istek: sınıra takıldı.";
            default -> status >= 500 ? "Sunucu hatası." : "Beklenmeyen durum kodu.";
        };
    }

    static Problem problemFor(int status) {
        if (status >= 200 && status < 300) return null;
        if (status >= 300 && status < 400) return Problem.API_REDIRECT;
        return switch (status) {
            case 401 -> Problem.API_UNAUTHORIZED;
            case 403 -> Problem.API_FORBIDDEN;
            case 404 -> Problem.API_NOT_FOUND;
            case 429 -> Problem.API_RATE_LIMIT;
            default -> status >= 500 ? Problem.API_SERVER : Problem.API_NOT_FOUND;
        };
    }

    /** Replaces every form of the key in {@code text} with ***. */
    public static String mask(String text, char[] key) {
        if (text == null || key == null || key.length == 0) {
            return text;
        }
        String k = new String(key);
        List<String> forms = new ArrayList<>(List.of(k, URLEncoder.encode(k, StandardCharsets.UTF_8),
                Base64.getEncoder().encodeToString(k.getBytes(StandardCharsets.UTF_8)),
                HexFormat.of().formatHex(k.getBytes(StandardCharsets.UTF_8))));
        forms.sort((a, b) -> b.length() - a.length());
        String out = text;
        for (String f : forms) {
            if (!f.isEmpty()) {
                out = out.replace(f, "***");
                out = out.replace(f.toUpperCase(Locale.ROOT), "***");
            }
        }
        return out;
    }

    /** UTF-8 when valid, otherwise windows-1254 (the store pages use it). */
    static String decode(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return new String(body, Cdn.PANEL_CHARSET);
        }
    }

    /** The computer's public IP (API keys are tied to it). Asks api.ipify.org; only on request. */
    public static String publicIp() throws IOException, InterruptedException {
        HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(HttpRequest.newBuilder(URI.create("https://api.ipify.org")).timeout(Duration.ofSeconds(15)).build(),
                        HttpResponse.BodyHandlers.ofString());
        String ip = r.body().strip();
        if (r.statusCode() != 200 || !ip.matches("[0-9a-fA-F:.]{3,45}")) {
            throw new IOException("IP adresi alınamadı (HTTP " + r.statusCode() + ")");
        }
        return ip;
    }
}
