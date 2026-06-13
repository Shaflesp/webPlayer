package MPD.service;

import MPD.config.AppSettings;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Service
public class SyncService {

    private final AppSettings settings;
    private final ConcurrentMap<String, SyncJob> jobs = new ConcurrentHashMap<>();

    public SyncService(AppSettings settings) {
        this.settings = settings;
    }

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
        String id  = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SyncJob job = new SyncJob(id, url);
        jobs.put(id, job);
        Thread.ofVirtual().name("yt-dlp-" + id).start(() -> run(job));
        return job;
    }

    public SyncJob getJob(String jobId) { return jobs.get(jobId); }

    /** Subscribe to a job's output lines; fires onLine for each, onDone when finished. */
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

    /** Returns all directories in music.dir that have an archive.txt. */
    public List<Map<String, Object>> listSyncedPlaylists() {
        Path base = Path.of(AppSettings.expandHome(settings.get("music.dir")));
        List<Map<String, Object>> list = new ArrayList<>();
        if (!Files.isDirectory(base)) return list;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                if (!Files.exists(dir.resolve("archive.txt"))) continue;
                long tracks = Files.list(dir)
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> !p.getFileName().toString().equals("archive.txt"))
                    .count();
                list.add(Map.of("name", dir.getFileName().toString(), "tracks", tracks));
            }
        } catch (IOException ignored) {}
        list.sort(Comparator.comparing(m -> m.get("name").toString()));
        return list;
    }

    // ── Browser detection ─────────────────────────────────────────────────────

    private String detectBrowser() {
        String home = System.getProperty("user.home");
        record Check(String dir, String flag) {}
        List<Check> candidates = List.of(
            new Check(".mozilla/firefox",                     "firefox"),
            new Check(".config/google-chrome",               "chrome"),
            new Check(".config/chromium",                    "chromium"),
            new Check(".config/BraveSoftware/Brave-Browser", "brave"),
            new Check(".config/microsoft-edge",              "edge"),
            new Check(".config/vivaldi",                     "vivaldi"),
            new Check(".config/opera",                       "opera")
        );
        for (Check c : candidates)
            if (Files.isDirectory(Path.of(home, c.dir()))) return c.flag();

        // Zen (Firefox fork)
        for (String zenBase : new String[]{ home + "/.zen", home + "/.config/zen" }) {
            Path base = Path.of(zenBase);
            if (!Files.isDirectory(base)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(base, "*.default*")) {
                for (Path profile : ds)
                    if (Files.isDirectory(profile))
                        return "firefox:" + profile.toAbsolutePath();
            } catch (IOException ignored) {}
        }
        return null;
    }

    // ── yt-dlp runner ─────────────────────────────────────────────────────────

    private void run(SyncJob job) {
        try {
            String musicDir = AppSettings.expandHome(settings.get("music.dir"));
            String browser  = detectBrowser();

            if (browser != null) {
                String label = browser.startsWith("firefox:") ? "Zen (Firefox)" : browser;
                job.lines.add("Browser detected: " + label);
            } else {
                job.lines.add("Warning: no browser profile found — age-restricted videos may fail.");
            }

            // 1. Fetch playlist title
            job.lines.add("Fetching playlist info…");
            List<String> titleCmd = new ArrayList<>(List.of(
                "yt-dlp", "--no-warnings", "--flat-playlist",
                "--print", "%(playlist_title)s", "--playlist-items", "1"
            ));
            if (browser != null) titleCmd.addAll(List.of("--cookies-from-browser", browser));
            titleCmd.add(job.url);

            String title = runProcess(titleCmd).stream()
                .filter(l -> !l.isBlank()).findFirst().orElse("").trim();

            if (title.isEmpty()) {
                job.lines.add("ERROR: could not get playlist title. Check URL or log into YouTube.");
                job.ok = false; job.done = true; return;
            }
            job.playlist = title;
            job.lines.add("Playlist identified: " + title);

            // 2. Download
            String targetDir   = musicDir + "/" + title;
            String archiveFile = targetDir + "/archive.txt";
            Files.createDirectories(Path.of(targetDir));
            job.lines.add("Target: " + targetDir);
            job.lines.add("Starting download…");

            List<String> dlCmd = new ArrayList<>(List.of(
                "yt-dlp", "-f", "bestaudio",
                "-x", "--audio-format", "best",
                "--embed-thumbnail", "--add-metadata",
                "--download-archive", archiveFile,
                "-o", targetDir + "/%(title)s.%(ext)s"
            ));
            if (browser != null) dlCmd.addAll(List.of("--cookies-from-browser", browser));
            dlCmd.add(job.url);

            ProcessBuilder pb = new ProcessBuilder(dlCmd).redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) job.lines.add(line);
            }
            int exit = proc.waitFor();
            job.ok   = (exit == 0);
            job.lines.add(job.ok
                ? "✓ Sync complete — " + targetDir
                : "✗ yt-dlp exited with code " + exit);

        } catch (Exception e) {
            job.lines.add("ERROR: " + e.getMessage());
            job.ok = false;
        } finally {
            job.done = true;
        }
    }

    private List<String> runProcess(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            lines = r.lines().toList();
        }
        p.waitFor();
        return lines;
    }
}
