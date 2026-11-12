package io.github.wreazycs.panelcontroller;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Project layout and settings. Defaults follow the layout the original Python tools used
 * (kaynak/, cikti/, veri/yedek/, veri/uretilen/); every directory can be overridden in a
 * properties file (see panel-controller.properties.example).
 */
public final class ProjectConfig {

    /** Default slot layout used by PlatinMarket themes. */
    public static final List<Slot> DEFAULT_SLOTS = List.of(
            new Slot("genel", "genel.css", "CSS / Genel"),
            new Slot("genel-js", "genel.js", "JS / Genel"),
            new Slot("anasayfa", "anasayfa.css", "CSS / Anasayfa"),
            new Slot("anasayfa-js", "anasayfa.js", "JS / Anasayfa"),
            new Slot("kategori", "kategori.css", "CSS / Kategori"),
            new Slot("kategori-js", "kategori.js", "JS / Kategori"),
            new Slot("urun", "urun.css", "CSS / Urun"),
            new Slot("urun-js", "urun.js", "JS / Urun"),
            new Slot("sepet", "sepet.css", "CSS / Sepet"),
            new Slot("sepet-js", "sepet.js", "JS / Sepet"));

    private final Path root;
    private final String cdnTemplate; // may be null
    private final List<Slot> slots;
    private final int budget;
    private final int slotTarget;
    private final Path sourceDir;
    private final Path outputDir;
    private final Path backupDir;
    private final Path generatedDir;

    private ProjectConfig(Path root, Properties p) {
        this.root = root;
        String cdn = p.getProperty("cdn", "").trim();
        this.cdnTemplate = cdn.isEmpty() ? null : cdn;
        this.budget = Integer.parseInt(p.getProperty("budget", "355000").trim());
        this.slotTarget = Integer.parseInt(p.getProperty("slotTarget", "65000").trim());
        this.sourceDir = root.resolve(p.getProperty("sourceDir", "kaynak").trim());
        this.outputDir = root.resolve(p.getProperty("outputDir", "cikti").trim());
        this.backupDir = root.resolve(p.getProperty("backupDir", "veri/yedek").trim());
        this.generatedDir = root.resolve(p.getProperty("generatedDir", "veri/uretilen").trim());
        this.slots = parseSlots(p.getProperty("slots"));
    }

    /** Loads settings; {@code configFile} may be null (defaults are used). */
    public static ProjectConfig load(Path root, Path configFile) throws IOException {
        Properties p = new Properties();
        if (configFile != null) {
            if (!Files.isRegularFile(configFile)) {
                throw new IOException("Config file not found: " + configFile);
            }
            try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return new ProjectConfig(root.toAbsolutePath().normalize(), p);
    }

    /** Settings given directly (the desktop app keeps them per project instead of in a file). */
    public static ProjectConfig of(Path root, Properties settings) {
        return new ProjectConfig(root.toAbsolutePath().normalize(), settings);
    }

    /** Format: {@code folder:file:Panel Name;folder:file:Panel Name} */
    static List<Slot> parseSlots(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT_SLOTS;
        }
        List<Slot> out = new ArrayList<>();
        for (String item : text.split(";")) {
            if (item.isBlank()) {
                continue;
            }
            String[] f = item.trim().split(":", 3);
            if (f.length < 2) {
                throw new IllegalArgumentException("Bad slot definition: " + item);
            }
            out.add(new Slot(f[0].trim(), f[1].trim(), f.length == 3 ? f[2].trim() : f[1].trim()));
        }
        return List.copyOf(out);
    }

    public Path root() { return root; }
    public String cdnTemplate() { return cdnTemplate; }
    public List<Slot> slots() { return slots; }
    public int budget() { return budget; }
    public int slotTarget() { return slotTarget; }
    public Path sourceDir() { return sourceDir; }
    public Path outputDir() { return outputDir; }
    public Path backupDir() { return backupDir; }
    public Path generatedDir() { return generatedDir; }

    /** Display form of a path relative to the project root, with forward slashes. */
    public String rel(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }
}
