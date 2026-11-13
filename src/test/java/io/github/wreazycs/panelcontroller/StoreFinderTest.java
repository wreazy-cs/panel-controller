package io.github.wreazycs.panelcontroller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StoreFinderTest {
    private static HttpServer server;
    private static URI base;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        page("/open", 200, "<link href=\"https://platincdn.com/4426/tema/genel.css?v=3\">"
                + "<script src=\"//platincdn.com/4426/tema/genel.js\"></script>"
                + "<img src=\"https://other.example.com/77/tema/x.js\">");
        page("/escaped", 200, "{\"css\":\"https:\\/\\/platincdn.com\\/12\\/tema\\/genel.css\"}");
        page("/closed", 403, "forbidden");
        page("/login", 401, "login");
        page("/missing", 404, "nope");
        page("/plain", 200, "<html>login form, no theme files here</html>");
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().add("Location", "/open");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void page(String path, int status, String body) {
        server.createContext(path, ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void addressesAreTurnedIntoHttps() {
        assertEquals("https://icelektronik.com.tr", StoreFinder.normalize("icelektronik.com.tr").orElseThrow().toString());
        assertEquals("https://www.x.com/a", StoreFinder.normalize("  http://www.x.com/a ").orElseThrow().toString());
        assertEquals("https://x.com", StoreFinder.normalize("HTTPS://x.com").orElseThrow().toString().toLowerCase());
        for (String bad : new String[] {"", "  ", null, "store", "a b.com", "ftp://x.com", "https://user:pw@x.com", "file:///c:/x"}) {
            assertTrue(StoreFinder.normalize(bad).isEmpty(), "should be refused: " + bad);
        }
    }

    @Test
    void mostUsedThemeAddressWins() {
        String html = "<a href=\"https://platincdn.com/4426/tema/genel.css\"><a href=\"//platincdn.com/4426/tema/genel.js?x=1\">"
                + "<a href=\"https://other.example.com/77/tema/x.js\">";
        assertEquals("https://platincdn.com/4426/tema/%s", StoreFinder.extract(html).orElseThrow());
    }

    @Test
    void escapedSlashesInJsonAreRead() {
        assertEquals("https://platincdn.com/12/tema/%s",
                StoreFinder.extract("{\"u\":\"https:\\/\\/platincdn.com\\/12\\/tema\\/genel.css\"}").orElseThrow());
    }

    @Test
    void pagesWithoutThemeLinksGiveNothing() {
        assertTrue(StoreFinder.extract("<html>nothing</html>").isEmpty());
        assertTrue(StoreFinder.extract("").isEmpty());
        assertTrue(StoreFinder.extract("https://platincdn.com/abc/tema/genel.css").isEmpty(), "store number must be digits");
    }

    @Test
    void openStoreIsFound() {
        StoreFinder.Result r = StoreFinder.fetch(base.resolve("/open"));
        assertEquals(StoreFinder.Outcome.FOUND, r.outcome());
        assertEquals("https://platincdn.com/4426/tema/%s", r.cdn());
        assertEquals(StoreFinder.Outcome.FOUND, StoreFinder.fetch(base.resolve("/escaped")).outcome());
    }

    @Test
    void redirectsAreFollowed() {
        assertEquals(StoreFinder.Outcome.FOUND, StoreFinder.fetch(base.resolve("/moved")).outcome());
    }

    @Test
    void closedStoreIsReportedAsClosed() {
        assertEquals(StoreFinder.Outcome.CLOSED, StoreFinder.fetch(base.resolve("/closed")).outcome());
        StoreFinder.Result login = StoreFinder.fetch(base.resolve("/login"));
        assertEquals(StoreFinder.Outcome.CLOSED, login.outcome());
        assertEquals(401, login.status());
        assertEquals(null, login.cdn());
    }

    @Test
    void otherAnswersAreToldApart() {
        StoreFinder.Result missing = StoreFinder.fetch(base.resolve("/missing"));
        assertEquals(StoreFinder.Outcome.HTTP_ERROR, missing.outcome());
        assertEquals(404, missing.status());
        assertEquals(StoreFinder.Outcome.NOT_FOUND, StoreFinder.fetch(base.resolve("/plain")).outcome());
    }

    @Test
    void noAnswerIsUnreachable() {
        assertEquals(StoreFinder.Outcome.UNREACHABLE, StoreFinder.fetch(URI.create("http://127.0.0.1:1/")).outcome());
        assertFalse(StoreFinder.find("not an address").outcome() == StoreFinder.Outcome.FOUND);
        assertEquals(StoreFinder.Outcome.INVALID, StoreFinder.find("not an address").outcome());
    }
}
