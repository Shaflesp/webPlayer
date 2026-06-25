package MPD.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {

    @TempDir
    Path tempDir;

    private AppSettings newSettings() {
        // Isolated file — never touches the real ~/.config/webplayer/config.properties
        return AppSettings.forTesting(tempDir.resolve("config.properties"));
    }

    // ── Defaults ──────────────────────────────────────────────────────────────

    @Test
    void get_returnsDefaultWhenNoFileExists() {
        AppSettings s = newSettings();
        assertEquals("localhost", s.get("mpd.host"));
        assertEquals("6600",      s.get("mpd.port"));
    }

    @Test
    void get_returnsEmptyStringForUnknownKey() {
        AppSettings s = newSettings();
        assertEquals("", s.get("not.a.real.key"));
    }

    @Test
    void getAll_includesEveryDefaultKeyEvenWithNoFile() {
        AppSettings s = newSettings();
        Map<String, String> all = s.getAll();
        assertEquals("localhost",   all.get("mpd.host"));
        assertEquals("ellipse",     all.get("visualizer.mode"));
        assertEquals("/tmp/mpd.fifo", all.get("fifo.path"));
    }

    @Test
    void getInt_parsesValidIntegers() {
        AppSettings s = newSettings();
        assertEquals(6600, s.getInt("mpd.port"));
    }

    @Test
    void getInt_fallsBackToDefaultOnInvalidStoredValue() throws IOException {
        AppSettings s = newSettings();
        s.saveAll(Map.of("mpd.port", "not-a-number"));
        // saveAll trims/stores it verbatim — getInt should not throw, should
        // fall back to the DEFAULT's parsed value instead.
        assertEquals(6600, s.getInt("mpd.port"));
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Test
    void saveAll_persistsAndIsReadableByANewInstanceAtTheSamePath() throws IOException {
        Path file = tempDir.resolve("config.properties");
        AppSettings writer = AppSettings.forTesting(file);
        writer.saveAll(Map.of("ui.accentColor", "#ff0000"));

        // A SEPARATE instance pointed at the same file should see the saved value —
        // proves it's actually round-tripping through disk, not just an in-memory cache.
        AppSettings reader = AppSettings.forTesting(file);
        assertEquals("#ff0000", reader.get("ui.accentColor"));
    }

    @Test
    void saveAll_onlyUpdatesProvidedKeys_othersKeepPreviousValues() throws IOException {
        AppSettings s = newSettings();
        s.saveAll(Map.of("ui.accentColor", "#ff0000"));
        s.saveAll(Map.of("ui.vinylSpeed", "10")); // separate call, different key

        assertEquals("#ff0000", s.get("ui.accentColor"), "first save should not be wiped by the second");
        assertEquals("10",      s.get("ui.vinylSpeed"));
    }

    @Test
    void saveAll_silentlyIgnoresKeysNotInDefaults() throws IOException {
        AppSettings s = newSettings();
        s.saveAll(Map.of("totally.unknown.key", "value"));
        assertEquals("", s.get("totally.unknown.key"));
    }

    @Test
    void saveAll_trimsWhitespaceFromValues() throws IOException {
        AppSettings s = newSettings();
        s.saveAll(Map.of("mpd.host", "  myhost  "));
        assertEquals("myhost", s.get("mpd.host"));
    }

    @Test
    void getAll_reflectsSavedValuesOverDefaults() throws IOException {
        AppSettings s = newSettings();
        s.saveAll(Map.of("mpd.host", "192.168.1.50"));

        Map<String, String> all = s.getAll();
        assertEquals("192.168.1.50", all.get("mpd.host"));
        // Untouched keys still show their default
        assertEquals("6600", all.get("mpd.port"));
    }

    // ── Reload-on-change ──────────────────────────────────────────────────────

    @Test
    void get_picksUpExternalFileChangesOnNextCall() throws IOException, InterruptedException {
        Path file = tempDir.resolve("config.properties");
        AppSettings s = AppSettings.forTesting(file);
        s.saveAll(Map.of("mpd.host", "first-value"));
        assertEquals("first-value", s.get("mpd.host"));

        // Simulate an EXTERNAL process editing the file directly (not via this
        // instance) — mtime must advance enough for the poll-on-read check to
        // notice; sleeping briefly avoids same-millisecond mtime flakiness.
        Thread.sleep(50);
        Properties raw = new Properties();
        raw.setProperty("mpd.host", "changed-externally");
        try (var os = Files.newOutputStream(file)) { raw.store(os, null); }

        assertEquals("changed-externally", s.get("mpd.host"),
            "should reload from disk when the file's mtime has changed");
    }

    // ── expandHome() ──────────────────────────────────────────────────────────

    @Test
    void expandHome_expandsTildeSlash() {
        String expected = System.getProperty("user.home") + "/Music";
        assertEquals(expected, AppSettings.expandHome("~/Music"));
    }

    @Test
    void expandHome_expandsBareTilde() {
        assertEquals(System.getProperty("user.home"), AppSettings.expandHome("~"));
    }

    @Test
    void expandHome_leavesAbsolutePathsUnchanged() {
        assertEquals("/already/absolute", AppSettings.expandHome("/already/absolute"));
    }
}
