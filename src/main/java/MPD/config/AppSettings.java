package MPD.config;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads/writes ~/.config/webplayer/config.properties.
 * Auto-reloads when the file changes on disk.
 * Injected wherever settings are needed via @Autowired / constructor injection.
 */
@Service
public class AppSettings {

    private static final Path FILE = Path.of(
        System.getProperty("user.home"), ".config", "webplayer", "config.properties"
    );

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
        DEFAULTS.setProperty("stream.url",          "");
        DEFAULTS.setProperty("visualizer.mode",     "ellipse");
        DEFAULTS.setProperty("fifo.path",           "/tmp/mpd.fifo");
        DEFAULTS.setProperty("yt.cookiesFile",      "");
    }

    private volatile Properties cache = new Properties();
    private volatile long fileMtime   = 0;

    // ── Public API ────────────────────────────────────────────────────────────

    public String get(String key) {
        maybeReload();
        String v = cache.getProperty(key);
        return v != null ? v : DEFAULTS.getProperty(key, "");
    }

    public int getInt(String key) {
        try { return Integer.parseInt(get(key).trim()); }
        catch (NumberFormatException e) {
            try { return Integer.parseInt(DEFAULTS.getProperty(key, "0").trim()); }
            catch (NumberFormatException e2) { return 0; }
        }
    }

    /** All keys: defaults overridden by file values. */
    public Map<String, String> getAll() {
        maybeReload();
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : DEFAULTS.stringPropertyNames()) out.put(k, DEFAULTS.getProperty(k));
        for (String k : cache.stringPropertyNames())    out.put(k, cache.getProperty(k));
        return out;
    }

    /** Merge updates (known keys only) and persist to disk. */
    public synchronized void saveAll(Map<String, String> updates) throws IOException {
        maybeReload();
        Properties toWrite = new Properties();
        toWrite.putAll(DEFAULTS);
        toWrite.putAll(cache);
        for (var e : updates.entrySet())
            if (DEFAULTS.containsKey(e.getKey()))
                toWrite.setProperty(e.getKey(), e.getValue().trim());
        Files.createDirectories(FILE.getParent());
        try (OutputStream os = Files.newOutputStream(FILE)) {
            toWrite.store(os, "WebPlayer config");
        }
        reload();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void maybeReload() {
        try {
            if (Files.exists(FILE)) {
                long mtime = Files.getLastModifiedTime(FILE).toMillis();
                if (mtime != fileMtime) reload();
            }
        } catch (IOException ignored) {}
    }

    private synchronized void reload() {
        Properties p = new Properties();
        try {
            if (Files.exists(FILE)) {
                try (InputStream is = Files.newInputStream(FILE)) { p.load(is); }
                fileMtime = Files.getLastModifiedTime(FILE).toMillis();
            }
        } catch (IOException ignored) {}
        cache = p;
    }

    public static String expandHome(String path) {
        if (path != null && (path.startsWith("~/") || path.equals("~")))
            return System.getProperty("user.home") + path.substring(1);
        return path;
    }
}
