package MPD.service;

import MPD.DependencyManager;
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

    private static final String INVISIBLE = "\u3164";
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppSettings       settings;
    private final DependencyManager deps;
    private final ConcurrentMap<String, SyncJob> jobs = new ConcurrentHashMap<>();

    public SyncService(AppSettings settings, DependencyManager deps) {
        this.settings = settings;
        this.deps     = deps;
    }

    // ── Job model ─────────────────────────────────────────────────────────────

    public static class SyncJob {
        public final String jobId;
        public final String url;
        public final CopyOnWriteArrayList<String> lines = new CopyOnWriteArrayList<>();
        public volatile boolean done = false, ok = false;
        public volatile String  playlist = null;
        SyncJob(String id, String url) { this.jobId = id; this.url = url; }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public SyncJob startJob(String url) {
        String  id  = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SyncJob job = new SyncJob(id, url);
        jobs.put(id, job);
        if (!deps.isYtDlpReady()) {
            job.lines.add("ERROR: yt-dlp is not available. Check Settings → Dependencies.");
            job.done = true;
        } else {
            Thread.ofVirtual().name("yt-dlp-" + id).start(() -> run(job));
        }
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

    // ── Playlist registry (CSV) ───────────────────────────────────────────────

    public static class PlaylistEntry {
        public final String name, url, lastSynced;
        public final long tracks;
        public PlaylistEntry(String name, String url, String lastSynced, long tracks) {
            this.name = name; this.url = url; this.lastSynced = lastSynced; this.tracks = tracks;
        }
    }

    public List<PlaylistEntry> listSyncedPlaylists() {
        Path base = musicBase();
        Map<String, String[]> csv = readCsv(base);
        List<PlaylistEntry> result = new ArrayList<>();
        if (!Files.isDirectory(base)) return result;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir) || !Files.exists(dir.resolve("archive.txt"))) continue;
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
                int c1 = line.indexOf(','), c2 = c1 >= 0 ? line.indexOf(',', c1 + 1) : -1;
                if (c1 < 0) continue;
                String n = line.substring(0, c1).trim();
                String u = c2 >= 0 ? line.substring(c1+1, c2).trim() : line.substring(c1+1).trim();
                String t = c2 >= 0 ? line.substring(c2+1).trim() : "";
                map.put(n, new String[]{ u, t });
            }
        } catch (IOException ignored) {}
        return map;
    }

    private void writeCsv(Path base, Map<String, String[]> map) {
        try (BufferedWriter w = Files.newBufferedWriter(base.resolve("webplayer-playlists.csv"))) {
            w.write("# WebPlayer playlist registry — name,url,lastSynced\n");
            for (var e : map.entrySet())
                w.write(e.getKey() + "," + e.getValue()[0] + "," + e.getValue()[1] + "\n");
        } catch (IOException ignored) {}
    }

    private Path musicBase() {
        return Path.of(AppSettings.expandHome(settings.get("music.dir")));
    }

    // ── Browser detection ─────────────────────────────────────────────────────

    private String detectBrowser() {
        String home = System.getProperty("user.home");
        String z = findMozillaProfile("firefox",  home + "/.zen");                                   if (z != null) return z;
        String f = findMozillaProfile("firefox",  home + "/.mozilla/firefox");                       if (f != null) return f;
        String c = findChromiumProfile("chrome",  home + "/.config/google-chrome");                  if (c != null) return c;
        String h = findChromiumProfile("chromium",home + "/.config/chromium");                       if (h != null) return h;
        String b = findChromiumProfile("brave",   home + "/.config/BraveSoftware/Brave-Browser");    if (b != null) return b;
        String e = findChromiumProfile("edge",    home + "/.config/microsoft-edge");                 if (e != null) return e;
        String v = findChromiumProfile("vivaldi", home + "/.config/vivaldi");                        if (v != null) return v;
        return findChromiumProfile("opera",   home + "/.config/opera");
    }

    private String findMozillaProfile(String flag, String basePath) {
        return findCookieFile(flag, basePath, "cookies.sqlite");
    }

    private String findChromiumProfile(String flag, String basePath) {
        return findCookieFile(flag, basePath, "Cookies");
    }

    private String findCookieFile(String flag, String basePath, String cookieFilename) {
        Path base = Path.of(basePath);
        if (!Files.isDirectory(base)) return null;
        try (var stream = Files.walk(base, 3)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals(cookieFilename) && !Files.isDirectory(p))
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException ex) { return 0L; }
                    }))
                    .map(p -> flag + ":" + p.getParent().toAbsolutePath())
                    .orElse(null);
        } catch (IOException ex) { return null; }
    }

    // ── yt-dlp runner ─────────────────────────────────────────────────────────

    private void run(SyncJob job) {
        try {
            String musicDir = AppSettings.expandHome(settings.get("music.dir"));
            String ytdlp    = deps.getYtDlpPath();   // bundled binary or system fallback
            String browser  = detectBrowser();

            if (browser == null)
                job.lines.add("Warning: no browser found — downloading anonymously.");

            // 1. Fetch playlist title
            job.lines.add("Fetching playlist info…");
            List<String> titleArgs = new ArrayList<>(List.of(
                    ytdlp, "--no-warnings", "--flat-playlist",
                    "--print", "%(playlist_title)s", "--playlist-items", "1"
            ));
            if (browser != null) titleArgs.addAll(List.of("--cookies-from-browser", browser));
            titleArgs.add(job.url);

            String title = exec(titleArgs).stream().filter(l -> !l.isBlank()).findFirst().orElse("").trim();
            if (title.isEmpty()) {
                job.lines.add("ERROR: could not get playlist title."); job.done = true; return;
            }
            job.playlist = title;
            job.lines.add("Playlist identified: " + title);

            // 2. Download into %(id)s/ subfolders
            String targetDir   = musicDir + "/" + title;
            String archiveFile = targetDir + "/archive.txt";
            Files.createDirectories(Path.of(targetDir));
            job.lines.add("Target: " + targetDir);
            job.lines.add("Starting download…");

            List<String> dlArgs = new ArrayList<>(List.of(
                    ytdlp, "-i",
                    "-f",  "bestaudio[acodec=opus]/bestaudio",
                    "-x",  "--audio-format", "best",
                    "--embed-thumbnail", "--add-metadata",
                    "--download-archive", archiveFile,
                    "-o",  targetDir + "/%(id)s/%(title).230B.%(ext)s"
            ));
            if (browser != null) dlArgs.addAll(List.of("--cookies-from-browser", browser));
            dlArgs.add(job.url);

            Process proc = new ProcessBuilder(dlArgs).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) job.lines.add(line);
            }
            int exit = proc.waitFor();
            job.ok = (exit == 0);
            if (!job.ok) { job.lines.add("✗ yt-dlp exited with code " + exit); job.done = true; return; }

            // 3. Move files out of ID subfolders, deduplicate with invisible char
            job.lines.add("Extracting songs from temporary folders…");
            extractIdFolders(Path.of(targetDir), job);
            job.lines.add("✓ Sync complete — " + targetDir);

            // 4. Save URL to CSV + trigger MPD update
            savePlaylistEntry(title, job.url);
            job.lines.add("Updating MPD library…");
            try (MPDClient mpd = new MPDClient(settings.get("mpd.host"), settings.getInt("mpd.port"))) {
                mpd.command("update");
                job.lines.add("✓ MPD library updated.");
            } catch (Exception e) {
                job.lines.add("Warning: MPD update failed — " + e.getMessage());
            }

        } catch (Exception e) {
            job.lines.add("ERROR: " + e.getMessage()); job.ok = false;
        } finally {
            job.done = true;
        }
    }

    private void extractIdFolders(Path targetDir, SyncJob job) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(targetDir)) {
            for (Path idFolder : ds) {
                if (!Files.isDirectory(idFolder) || idFolder.getFileName().toString().length() != 11) continue;
                try (var files = Files.list(idFolder)) {
                    for (Path src : files.filter(p -> !Files.isDirectory(p)).toList()) {
                        String fn = src.getFileName().toString();
                        int dot = fn.lastIndexOf('.');
                        String base = dot >= 0 ? fn.substring(0, dot) : fn;
                        String ext  = dot >= 0 ? fn.substring(dot) : "";
                        Path dest = targetDir.resolve(base + ext);
                        while (Files.exists(dest)) { base += INVISIBLE; dest = targetDir.resolve(base + ext); }
                        Files.move(src, dest);
                    }
                }
                try { Files.delete(idFolder); }
                catch (IOException e) { job.lines.add("Warning: could not remove " + idFolder.getFileName()); }
            }
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