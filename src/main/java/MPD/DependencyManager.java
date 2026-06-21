package MPD;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * Manages runtime dependencies that can't live inside the JAR:
 * <p> 
 *   - yt-dlp  : bundled in /native/yt-dlp at build time, extracted to
 *               ~/.local/share/webplayer/bin/yt-dlp on first run.
 *   - MPD     : must be installed system-wide (audio hardware access required).
 *   - ffmpeg  : must be installed system-wide (used by yt-dlp for conversion).
 * <p>   
 * SyncService injects this bean and calls getYtDlpPath() instead of
 * hard-coding "yt-dlp", so the bundled binary is used when available.
 */
@Service
public class DependencyManager {

    private static final Logger log = LoggerFactory.getLogger(DependencyManager.class);

    private static final Path APP_DIR  =
        Path.of(System.getProperty("user.home"), ".local", "share", "webplayer");
    private static final Path BIN_DIR  = APP_DIR.resolve("bin");
    private static final Path YT_DLP   = BIN_DIR.resolve("yt-dlp");

    /** Resolved at startup; never null after init(). */
    private String ytDlpExecutable = "yt-dlp";   // fallback: rely on PATH

    // Dependency check results — exposed for the /status endpoint
    private boolean ytDlpReady  = false;
    private boolean mpdReady    = false;
    private boolean ffmpegReady = false;
    private String  ytDlpNote   = "";

    @PostConstruct
    public void init() {
        extractYtDlp();
        checkSystemDeps();
        logSummary();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Path to the yt-dlp executable (bundled or system fallback). */
    public String getYtDlpPath() { return ytDlpExecutable; }

    /** True if yt-dlp is available (bundled or on PATH). */
    public boolean isYtDlpReady()  { return ytDlpReady;  }
    public boolean isMpdReady()    { return mpdReady;    }
    public boolean isFfmpegReady() { return ffmpegReady; }

    /** Map suitable for JSON serialisation (used by ConfigController /status). */
    public Map<String, Object> statusMap() {
        return Map.of(
            "ytdlp",  Map.of("ok", ytDlpReady,  "path", ytDlpExecutable, "note", ytDlpNote),
            "mpd",    Map.of("ok", mpdReady),
            "ffmpeg", Map.of("ok", ffmpegReady)
        );
    }

    // ── yt-dlp extraction ─────────────────────────────────────────────────────

    private void extractYtDlp() {
        // If already extracted, just verify it's still executable
        if (Files.isExecutable(YT_DLP)) {
            ytDlpExecutable = YT_DLP.toString();
            ytDlpReady      = true;
            ytDlpNote       = "bundled (cached)";
            return;
        }

        // Try to extract from the bundled resource
        try (InputStream bundled = getClass().getResourceAsStream("/native/yt-dlp")) {
            if (bundled == null) {
                // JAR was built without the binary — fall back to system PATH
                log.warn("No bundled yt-dlp found in JAR resources. Falling back to system PATH.");
                ytDlpNote = "not bundled — using system PATH";
                checkYtDlpOnPath();
                return;
            }

            Files.createDirectories(BIN_DIR);
            Files.copy(bundled, YT_DLP, StandardCopyOption.REPLACE_EXISTING);

            // chmod +x
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(YT_DLP));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(YT_DLP, perms);

            ytDlpExecutable = YT_DLP.toString();
            ytDlpReady      = true;
            ytDlpNote       = "bundled → " + YT_DLP;
            log.info("Extracted bundled yt-dlp → {}", YT_DLP);

        } catch (IOException e) {
            log.warn("Could not extract bundled yt-dlp: {} — falling back to system PATH", e.getMessage());
            ytDlpNote = "extraction failed: " + e.getMessage();
            checkYtDlpOnPath();
        }
    }

    private void checkYtDlpOnPath() {
        if (probe("yt-dlp", "--version")) {
            ytDlpExecutable = "yt-dlp";
            ytDlpReady      = true;
            ytDlpNote       = "system PATH";
        } else {
            ytDlpReady = false;
            ytDlpNote  = "not found — YouTube sync unavailable";
        }
    }

    // ── System dependency checks ──────────────────────────────────────────────

    private void checkSystemDeps() {
        mpdReady    = probe("mpd",    "--version");
        ffmpegReady = probe("ffmpeg", "-version");
    }

    /**
     * Runs {@code cmd} in a subprocess and returns true if it exits with 0.
     * Used only to test whether a binary is reachable — any output is discarded.
     */
    private static boolean probe(String... cmd) {
        try {
            return new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void logSummary() {
        log.info("──────────────── WebPlayer dependencies ────────────────");
        logDep("yt-dlp ", ytDlpReady,  ytDlpNote.isEmpty() ? ytDlpExecutable : ytDlpNote);
        logDep("MPD    ", mpdReady,    mpdReady    ? "found" : "NOT FOUND — install: sudo pacman -S mpd");
        logDep("ffmpeg ", ffmpegReady, ffmpegReady ? "found" : "NOT FOUND — install: sudo pacman -S ffmpeg");
        log.info("────────────────────────────────────────────────────────");
    }

    private static void logDep(String name, boolean ok, String note) {
        if (ok) log.info("  ✓ {}  {}", name, note);
        else    log.warn("  ✗ {}  {}", name, note);
    }
}
