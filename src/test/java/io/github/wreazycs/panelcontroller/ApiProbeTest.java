package io.github.wreazycs.panelcontroller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs against a local fake API; the real API is tried from the app with the person's own key. */
class ApiProbeTest {
    private static final char[] KEY = "pm-test-KEY+/=123".toCharArray();
    private HttpServer server;
    private final AtomicInteger otherHostHits = new AtomicInteger();
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // echoes the query back, the way a careless API might (the key must still not show)
        server.createContext("/echo", ex -> reply(ex, 200, "{\"you sent\":\"" + ex.getRequestURI().getRawQuery() + "\","
                + "\"header\":\"" + ex.getRequestHeaders().getFirst("X-Api-Key") + "\"}"));
        server.createContext("/reform/products/info.json", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            if (q == null || !q.contains("access_token=")) {
                reply(ex, 200, "{\"response\":{\"header\":[],\"data\":null,\"error\":{\"code\":100,\"message\":\"Access token required\",\"scope\":\"REMOTE\"}}}");
            } else {
                reply(ex, 200, "{\"response\":{\"header\":{\"total\":1},\"data\":{\"name\":\"Magaza\"},\"error\":{\"code\":null,\"message\":null,\"scope\":null}}}");
            }
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://evil.example/steal");
            reply(ex, 302, "");
        });
        server.createContext("/steal", ex -> {
            otherHostHits.incrementAndGet();
            reply(ex, 200, "stolen");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        }
        ex.close();
    }

    private ApiProbe.Result get(String path, ApiProbe.Auth auth, String name) throws Exception {
        return ApiProbe.get(new ApiProbe.Request(base, path, auth, name, ""), KEY, true);
    }

    @Test
    void keyNeverAppearsInAnyForm() throws Exception {
        ApiProbe.Result q = get("/echo", ApiProbe.Auth.QUERY, "access_token");
        ApiProbe.Result h = get("/echo", ApiProbe.Auth.HEADER, "X-Api-Key");
        for (ApiProbe.Result r : new ApiProbe.Result[] {q, h}) {
            String all = r.url() + r.body() + r.headers();
            assertFalse(all.contains("KEY+"), all);
            assertFalse(all.contains("KEY%2B"), all);
            assertTrue(all.contains("***"), all);
        }
    }

    @Test
    void envelopeErrorWithHttp200IsAFailure() throws Exception {
        ApiProbe.Result none = ApiProbe.get(new ApiProbe.Request(base, "/reform/products/info.json", ApiProbe.Auth.NONE, "", ""),
                new char[0], true);
        assertEquals(200, none.status());
        assertFalse(none.ok(), "HTTP 200 with error.code must not count as success");
        assertEquals(Problem.API_REJECTED, none.problem().problem());
        assertTrue(none.meaning().contains("100"), none.meaning());

        ApiProbe.Result ok = get("/reform/products/info.json", ApiProbe.Auth.QUERY, "access_token");
        assertTrue(ok.ok(), ok.meaning());
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        ApiProbe.Result r = get("/redirect", ApiProbe.Auth.QUERY, "access_token");
        assertEquals(302, r.status());
        assertEquals(Problem.API_REDIRECT, r.problem().problem());
        assertEquals(0, otherHostHits.get());
    }

    @Test
    void plainHttpToARealHostIsRefusedAndKeyIsRequired() throws Exception {
        ApiProbe.Result http = ApiProbe.get(new ApiProbe.Request("http://developer.platinmarket.com", "/x", ApiProbe.Auth.QUERY,
                "access_token", ""), KEY);
        assertEquals(Problem.API_HTTPS_ONLY, http.problem().problem());
        assertEquals(0, http.status());

        ApiProbe.Result noKey = ApiProbe.get(new ApiProbe.Request("https://developer.platinmarket.com", "/x", ApiProbe.Auth.QUERY,
                "access_token", ""), new char[0]);
        assertEquals(Problem.API_NO_KEY, noKey.problem().problem());
    }

    @Test
    void presetsAreReadOnlyAndAvoidPersonalData() {
        for (ApiProbe.Preset p : ApiProbe.PRESETS) {
            assertTrue(p.path().startsWith("/reform/") && p.path().endsWith(".json"), p.path());
            assertFalse(p.path().matches(".*/(members|member_groups|orders|claims|coupons)/.*"), "personal data: " + p.path());
            assertFalse(p.path().matches(".*/(delete|create_or_update|create_or_update_basic|update|change_status|bulk_update)\\.json"),
                    "writes: " + p.path());
        }
    }
}
