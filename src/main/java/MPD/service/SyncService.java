package MPD.service;

import MPD.MPDClient;
import MPD.config.AppSettings;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class SyncService {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppSettings settings;
    private final ConcurrentMap<String, SyncJob> jobs = new ConcurrentHashMap<>();

    public SyncService(AppSettings settings) { this.settings = settings; }

    // ── Job model ─────────────────────────────────────────────────────────────

    public static class SyncJob {
        public final String jobId;
        public final String url;
        public final CopyOnWriteArrayList<String> lines = new CopyOnWriteArrayList<>();
        public volatile boolean done     = false;
        public volatile boolean ok       = false;
        public volatile String  playlist = null;

        SyncJob(String id, String url) { this.jobId = id; this.url = url; }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public SyncJob startJob(String url) {
        String  id  = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SyncJob job = new SyncJob(id, url);
        jobs.put(id, job);
        Thread.ofVirtual().name("yt-dlp-" + id).start(() -> run(job));
        return job;
    }

    public SyncJob getJob(String jobId) { return jobs.get(jobId); }

    public void subscribe(SyncJob job, Consumer<String> onLine, Runnable onDone) {
        Thread.ofVirtual().start(() -> {
            int idx = 0;
            try {
                while (true) {
                    while (idx < job.lines.size()) onLine.accept(job.lines.get(idx++));
                    if (job.done && idx >= job.lines.size()) { onDone.run(); return; }
                    Thread.sleep(80);
                }
            } catch (InterruptedException ignored) {}
        });
    }

    // ── Playlist registry ─────────────────────────────────────────────────────
    //   Stored at {music.dir}/webplayer-playlists.csv
    //   Format:  name,url,lastSynced
    //   (splits on first two commas — URLs may contain commas in query strings)

    public static class PlaylistEntry {
        public final String name;
        public final String url;
        public final String lastSynced;
        public final long   tracks;

        public PlaylistEntry(String name, String url, String lastSynced, long tracks) {
            this.name = name; this.url = url;
            this.lastSynced = lastSynced; this.tracks = tracks;
        }
    }

    public List<PlaylistEntry> listSyncedPlaylists() {
        Path base = musicBase();
        Map<String, String[]> csv = readCsv(base); // name → [url, lastSynced]

        List<PlaylistEntry> result = new ArrayList<>();
        if (!Files.isDirectory(base)) return result;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                if (!Files.exists(dir.resolve("archive.txt"))) continue;
                String name = dir.getFileName().toString();
                long tracks;
                try (var s = Files.list(dir)) {
                    tracks = s.filter(p -> !Files.isDirectory(p))
                            .filter(p -> !p.getFileName().toString().equals("archive.txt"))
                            .count();
                }
                String[] meta = csv.getOrDefault(name, new String[]{"", ""});
                result.add(new PlaylistEntry(name, meta[0], meta[1], tracks));
            }
        } catch (IOException ignored) {}

        result.sort(Comparator.comparing(e -> e.name));
        return result;
    }

    private synchronized void savePlaylistEntry(String name, String url) {
        Path base = musicBase();
        Map<String, String[]> csv = readCsv(base);
        csv.put(name, new String[]{ url, LocalDateTime.now().format(TS_FMT) });
        writeCsv(base, csv);
    }

    private Map<String, String[]> readCsv(Path base) {
        Path file = base.resolve("webplayer-playlists.csv");
        Map<String, String[]> map = new LinkedHashMap<>();
        if (!Files.exists(file)) return map;
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int c1 = line.indexOf(',');
                int c2 = c1 >= 0 ? line.indexOf(',', c1 + 1) : -1;
                if (c1 < 0) continue;
                String name       = line.substring(0, c1).trim();
                String url        = c2 >= 0 ? line.substring(c1 + 1, c2).trim()
                        : line.substring(c1 + 1).trim();
                String lastSynced = c2 >= 0 ? line.substring(c2 + 1).trim() : "";
                map.put(name, new String[]{ url, lastSynced });
            }
        } catch (IOException ignored) {}
        return map;
    }

    private void writeCsv(Path base, Map<String, String[]> map) {
        Path file = base.resolve("webplayer-playlists.csv");
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("# WebPlayer playlist registry — name,url,lastSynced\n");
            for (var e : map.entrySet())
                w.write(e.getKey() + "," + e.getValue()[0] + "," + e.getValue()[1] + "\n");
        } catch (IOException ignored) {}
    }

    private Path musicBase() {
        return Path.of(AppSettings.expandHome(settings.get("music.dir")));
    }

    // ── Browser detection — mirrors sync-music.sh most-recently-used logic ───

    private record BrowserCandidate(String name, String flag, long mtime) {}

    /**
     * Finds the most recently active browser profile across all installed
     * browsers, exactly as sync-music.sh does:
     * - Firefox/Zen: looks for cookies.sqlite (most recently modified)
     * - Chromium-based: looks for the Cookies file (most recently modified)
     * Returns the --cookies-from-browser flag value, or null if no browser found.
     */
    private String detectBrowser() {
        String home = System.getProperty("user.home");
        List<BrowserCandidate> candidates = new ArrayList<>();

        // Mozilla-based (cookies.sqlite)
        checkMozilla(candidates, "Firefox", "firefox", home + "/.mozilla/firefox");
        checkMozilla(candidates, "Zen",     "firefox", home + "/.zen");

        // Chromium-based (Cookies file)
        checkChromium(candidates, "Google Chrome", "chrome",    home + "/.config/google-chrome");
        checkChromium(candidates, "Chromium",      "chromium",  home + "/.config/chromium");
        checkChromium(candidates, "Brave",         "brave",     home + "/.config/BraveSoftware/Brave-Browser");
        checkChromium(candidates, "Edge",          "edge",      home + "/.config/microsoft-edge");
        checkChromium(candidates, "Vivaldi",       "vivaldi",   home + "/.config/vivaldi");
        checkChromium(candidates, "Opera",         "opera",     home + "/.config/opera");

        return candidates.stream()
                .max(Comparator.comparingLong(BrowserCandidate::mtime))
                .map(BrowserCandidate::flag)
                .orElse(null);
    }

    /** Walks basePath up to depth 3 looking for cookies.sqlite files. */
    private void checkMozilla(List<BrowserCandidate> out,
                              String name, String flag, String basePath) {
        Path base = Path.of(basePath);
        if (!Files.isDirectory(base)) return;
        try (var stream = Files.walk(base, 3)) {
            stream.filter(p -> p.getFileName().toString().equals("cookies.sqlite"))
                    .forEach(p -> {
                        try {
                            long mtime = Files.getLastModifiedTime(p).toMillis();
                            // flag value: "firefox:/path/to/profile"
                            out.add(new BrowserCandidate(
                                    name,
                                    flag + ":" + p.getParent().toAbsolutePath(),
                                    mtime));
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    /** Walks basePath up to depth 3 looking for Cookies files (no extension). */
    private void checkChromium(List<BrowserCandidate> out,
                               String name, String flag, String basePath) {
        Path base = Path.of(basePath);
        if (!Files.isDirectory(base)) return;
        try (var stream = Files.walk(base, 3)) {
            stream.filter(p -> p.getFileName().toString().equals("Cookies")
                            && !Files.isDirectory(p))
                    .forEach(p -> {
                        try {
                            long mtime = Files.getLastModifiedTime(p).toMillis();
                            // flag value: "chrome:/path/to/profile"
                            out.add(new BrowserCandidate(
                                    name,
                                    flag + ":" + p.getParent().toAbsolutePath(),
                                    mtime));
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    // ── yt-dlp runner ─────────────────────────────────────────────────────────

    private void run(SyncJob job) {
        try {
            String musicDir = AppSettings.expandHome(settings.get("music.dir"));
            String browser  = detectBrowser();

            if (browser != null) {
                String label = browser.startsWith("firefox:") ? "Zen/Firefox"
                        : browser.split(":")[0];
                job.lines.add("Browser detected: " + label + " (most recently used)");
            } else {
                job.lines.add("Warning: no browser profile found — age-restricted videos may fail.");
            }

            // 1. Fetch playlist title ──────────────────────────────────────────
            job.lines.add("Fetching playlist info…");
            List<String> titleArgs = new ArrayList<>(List.of(
                    "yt-dlp", "--no-warnings", "--flat-playlist",
                    "--print", "%(playlist_title)s", "--playlist-items", "1"
            ));
            if (browser != null) titleArgs.addAll(List.of("--cookies-from-browser", browser));
            titleArgs.add(job.url);

            String title = exec(titleArgs).stream()
                    .filter(l -> !l.isBlank()).findFirst().orElse("").trim();

            if (title.isEmpty()) {
                job.lines.add("ERROR: could not get playlist title. "
                        + "Check the URL or log into YouTube in your browser.");
                job.ok = false; job.done = true; return;
            }
            job.playlist = title;
            job.lines.add("Playlist identified: " + title);

            // 2. Download — arg list mirrors sync-music.sh exactly ─────────────
            String targetDir   = musicDir + "/" + title;
            String archiveFile = targetDir + "/archive.txt";
            Files.createDirectories(Path.of(targetDir));
            job.lines.add("Target: " + targetDir);
            job.lines.add("Starting download…");

            List<String> dlArgs = new ArrayList<>(List.of(
                    "yt-dlp",
                    //"-i",                                          // ignore unavailable videos
                    "--trim-filenames", "120",                     // prevent filename-length OS errors
                    "-f",  "bestaudio",
                    "-x",  "--audio-format", "best",
                    "--embed-thumbnail",
                    "--add-metadata",
                    "--download-archive", archiveFile,
                    "-o",  targetDir + "/%(title).100B.%(ext)s"   // cap title at 100 bytes
            ));
            if (browser != null) dlArgs.addAll(List.of("--cookies-from-browser", browser));
            dlArgs.add(job.url);

            Process proc = new ProcessBuilder(dlArgs).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) job.lines.add(line);
            }
            int exit = proc.waitFor();
            job.ok = (exit == 0);
            job.lines.add(job.ok
                    ? "✓ Sync complete — " + targetDir
                    : "✗ yt-dlp exited with code " + exit);

            // 3. On success: save URL to CSV + trigger MPD update ─────────────
            if (job.ok) {
                savePlaylistEntry(title, job.url);
                job.lines.add("Updating MPD library…");
                try (MPDClient mpd = new MPDClient(
                        settings.get("mpd.host"), settings.getInt("mpd.port"))) {
                    mpd.command("update");
                    job.lines.add("✓ MPD library updated.");
                } catch (Exception e) {
                    job.lines.add("Warning: MPD update failed — " + e.getMessage());
                }
            }

        } catch (Exception e) {
            job.lines.add("ERROR: " + e.getMessage());
            job.ok = false;
        } finally {
            job.done = true;
        }
    }

    private List<String> exec(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            lines = r.lines().collect(Collectors.toList());
        }
        p.waitFor();
        return lines;
    }
}