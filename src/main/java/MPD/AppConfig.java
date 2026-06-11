package MPD;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Singleton configuration store backed by
 * ~/.config/webplayer/config.properties.
 *
 * Automatically reloads when the file is modified on disk so changes
 * made via ConfigServlet take effect on the next request without a restart.
 */
public final class AppConfig {

    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".config", "webplayer", "config.properties"
    );

    // ── Defaults ─────────────────────────────────────────────────────────────
    private static final Properties DEFAULTS = new Properties();
    static {
        DEFAULTS.setProperty("mpd.host",            "localhost");
        DEFAULTS.setProperty("mpd.port",            "6600");
        DEFAULTS.setProperty("music.dir",           expandHome("~/Music"));
        DEFAULTS.setProperty("ui.accentColor",      "#7c3aed");
        DEFAULTS.setProperty("ui.vinylSpeed",       "6");
        DEFAULTS.setProperty("ui.bgOpacity",        "0.20");
        DEFAULTS.setProperty("player.pauseOnClose", "false");
        DEFAULTS.setProperty("player.pollInterval", "1000");
        DEFAULTS.setProperty("stream.url",          "");         // optional MPD httpd URL for real FFT
        DEFAULTS.setProperty("visualizer.mode",     "ellipse");  // ellipse | bar | off
        DEFAULTS.setProperty("fifo.path",           "/tmp/mpd.fifo"); // MPD FIFO output path
    }

    private static volatile Properties cache = new Properties();
    private static volatile long       fileMtime = 0;

    private AppConfig() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static String get(String key) {
        maybeReload();
        String v = cache.getProperty(key);
        return v != null ? v : DEFAULTS.getProperty(key, "");
    }

    public static int getInt(String key) {
        try { return Integer.parseInt(get(key).trim()); }
        catch (NumberFormatException e) {
            try { return Integer.parseInt(DEFAULTS.getProperty(key, "0").trim()); }
            catch (NumberFormatException e2) { return 0; }
        }
    }

    /** All keys merged: defaults overridden by file values. */
    public static Map<String, String> getAll() {
        maybeReload();
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : DEFAULTS.stringPropertyNames()) out.put(k, DEFAULTS.getProperty(k));
        for (String k : cache.stringPropertyNames())    out.put(k, cache.getProperty(k));
        return out;
    }

    /** Validate, merge and persist a map of updates. Only known keys are accepted. */
    public static synchronized void saveAll(Map<String, String> updates) throws IOException {
        maybeReload();
        Properties toWrite = new Properties();
        toWrite.putAll(DEFAULTS);          // start from defaults
        toWrite.putAll(cache);             // overlay current file values
        for (var e : updates.entrySet()) { // apply updates (known keys only)
            if (DEFAULTS.containsKey(e.getKey()))
                toWrite.setProperty(e.getKey(), e.getValue().trim());
        }
        Files.createDirectories(FILE.getParent());
        try (OutputStream os = Files.newOutputStream(FILE)) {
            toWrite.store(os, "WebPlayer config — managed by ConfigServlet");
        }
        reload();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static void maybeReload() {
        try {
            if (Files.exists(FILE)) {
                long mtime = Files.getLastModifiedTime(FILE).toMillis();
                if (mtime != fileMtime) reload();
            }
        } catch (IOException ignored) {}
    }

    private static synchronized void reload() {
        Properties p = new Properties();
        try {
            if (Files.exists(FILE)) {
                try (InputStream is = Files.newInputStream(FILE)) { p.load(is); }
                fileMtime = Files.getLastModifiedTime(FILE).toMillis();
            }
        } catch (IOException ignored) {}
        cache = p;
    }

    static String expandHome(String path) {
        if (path.startsWith("~/") || path.equals("~"))
            return System.getProperty("user.home") + path.substring(1);
        return path;
    }
}