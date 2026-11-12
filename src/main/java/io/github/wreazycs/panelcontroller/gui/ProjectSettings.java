package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.ProjectConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * Per-project settings (store CDN address, limits), kept by the app for each project folder. This
 * replaces the separate properties file the first version needed.
 */
final class ProjectSettings {
    static final int DEFAULT_BUDGET = 355_000;
    static final int DEFAULT_SLOT_TARGET = 65_000;

    private final Preferences node;
    final Path root;
    String cdn;
    int budget;
    int slotTarget;
    // API probe (the key itself is never kept here)
    String apiBase;
    String apiPath;
    String apiAuth;
    String apiName;
    String apiPrefix;
    /** Host the person confirmed the key may be sent to. */
    String apiTrustedHost;

    private ProjectSettings(Preferences app, Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.node = app.node("projects").node(hash(this.root.toString().toLowerCase(java.util.Locale.ROOT)));
        this.cdn = node.get("cdn", "");
        this.budget = node.getInt("budget", DEFAULT_BUDGET);
        this.slotTarget = node.getInt("slotTarget", DEFAULT_SLOT_TARGET);
        // defaults: PlatinMarket Reform API (developer.platinmarket.com), key as ?access_token=
        this.apiBase = node.get("apiBase", io.github.wreazycs.panelcontroller.ApiProbe.REFORM_BASE);
        this.apiPath = node.get("apiPath", "/reform/products/info.json");
        this.apiAuth = node.get("apiAuth", "QUERY");
        this.apiName = node.get("apiName", "access_token");
        this.apiPrefix = node.get("apiPrefix", "");
        this.apiTrustedHost = node.get("apiTrustedHost", "");
    }

    void saveApi() {
        node.put("apiBase", apiBase);
        node.put("apiPath", apiPath);
        node.put("apiAuth", apiAuth);
        node.put("apiName", apiName);
        node.put("apiPrefix", apiPrefix);
        node.put("apiTrustedHost", apiTrustedHost);
    }

    static ProjectSettings load(Preferences app, Path root) {
        ProjectSettings s = new ProjectSettings(app, root);
        s.migrateOldConfigFile(app);
        return s;
    }

    void save() {
        node.put("cdn", cdn == null ? "" : cdn.strip());
        node.putInt("budget", budget);
        node.putInt("slotTarget", slotTarget);
        node.put("path", root.toString());
    }

    ProjectConfig toConfig() {
        Properties p = new Properties();
        if (cdn != null && !cdn.isBlank()) {
            p.setProperty("cdn", cdn.strip());
        }
        p.setProperty("budget", String.valueOf(budget));
        p.setProperty("slotTarget", String.valueOf(slotTarget));
        return ProjectConfig.of(root, p);
    }

    /** Version 0.2-0.4 used a properties file chosen in the window; take its CDN address over once. */
    private void migrateOldConfigFile(Preferences app) {
        String old = app.get("config", "");
        if (old.isEmpty() || !cdn.isEmpty()) {
            return;
        }
        try (Reader r = Files.newBufferedReader(Path.of(old), StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            String c = p.getProperty("cdn", "").strip();
            if (!c.isEmpty()) {
                cdn = c;
                save();
            }
        } catch (IOException | RuntimeException e) {
            // old file gone or unreadable: nothing to take over
        }
        app.remove("config");
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
