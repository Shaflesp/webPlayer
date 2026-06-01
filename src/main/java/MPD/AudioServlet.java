package MPD;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.*;
import java.net.URLConnection;
import java.nio.file.*;

/**
 * Serves music files from the MPD music directory.
 *
 * GET /AudioServlet?uri=relative/path/to/file.mp3
 *
 * Supports HTTP Range (206 Partial Content) so the browser <audio> element
 * can seek freely. This completely replaces the MPD httpd output.
 *
 * Set MUSIC_DIR to match the music_directory in your mpd.conf.
 * You can also set the env var MPD_MUSIC_DIR to override at runtime.
 */
@WebServlet("/AudioServlet")
public class AudioServlet extends HttpServlet {

    // ── Configuration ──────────────────────────────────────────────────────────
    // Matches music_directory in mpd.conf. ~/Music expands to your home dir.
    private static final String MUSIC_DIR = resolveDir(
            System.getenv("MPD_MUSIC_DIR") != null
                    ? System.getenv("MPD_MUSIC_DIR")
                    : System.getProperty("user.home") + "/Music"
    );

    private static final int BUFFER = 64 * 1024;   // 64 KiB read buffer

    // ── GET ───────────────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String uri = req.getParameter("uri");
        if (uri == null || uri.isBlank()) {
            res.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing uri parameter");
            return;
        }

        // Sanitize: prevent path traversal
        Path base = Path.of(MUSIC_DIR).normalize();
        Path file = base.resolve(uri).normalize();
        if (!file.startsWith(base)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN, "Path traversal denied");
            return;
        }

        if (!Files.isRegularFile(file)) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found: " + uri);
            return;
        }

        long fileLen = Files.size(file);
        String mime  = guessMime(file.toString());

        res.setHeader("Accept-Ranges", "bytes");
        res.setContentType(mime);
        res.setHeader("Cache-Control", "no-store");

        // ── Range request (seek support) ──────────────────────────────────────
        String rangeHeader = req.getHeader("Range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            serveRange(req, res, file, fileLen, rangeHeader);
        } else {
            serveFull(res, file, fileLen);
        }
    }

    // ── HEAD (let browser probe file size for duration estimate) ─────────────

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String uri = req.getParameter("uri");
        if (uri == null || uri.isBlank()) { res.sendError(400); return; }

        Path base = Path.of(MUSIC_DIR).normalize();
        Path file = base.resolve(uri).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) { res.sendError(404); return; }

        res.setContentType(guessMime(file.toString()));
        res.setContentLengthLong(Files.size(file));
        res.setHeader("Accept-Ranges", "bytes");
    }

    // ── Serve helpers ─────────────────────────────────────────────────────────

    private void serveFull(HttpServletResponse res, Path file, long fileLen) throws IOException {
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentLengthLong(fileLen);
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = res.getOutputStream()) {
            transfer(in, out, 0, fileLen);
        }
    }

    private void serveRange(HttpServletRequest req, HttpServletResponse res,
                            Path file, long fileLen, String rangeHeader) throws IOException {
        // Parse "bytes=START-END"  (END may be absent)
        String[] parts = rangeHeader.substring(6).split("-", 2);
        long start, end;
        try {
            start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0].trim());
            end   = (parts.length < 2 || parts[1].isEmpty())
                    ? fileLen - 1
                    : Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            res.sendError(416, "Unparseable range: " + rangeHeader);
            return;
        }

        if (start < 0 || end >= fileLen || start > end) {
            res.setStatus(416);
            res.setHeader("Content-Range", "bytes */" + fileLen);
            return;
        }

        long length = end - start + 1;

        res.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        res.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLen);
        res.setContentLengthLong(length);

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             OutputStream out = res.getOutputStream()) {
            raf.seek(start);
            byte[] buf = new byte[BUFFER];
            long remaining = length;
            int n;
            while (remaining > 0 && (n = raf.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    private void transfer(InputStream in, OutputStream out, long skip, long length) throws IOException {
        if (skip > 0) in.skip(skip);
        byte[] buf = new byte[BUFFER];
        long   remaining = length;
        int n;
        while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    // ── MIME detection ────────────────────────────────────────────────────────

    private String guessMime(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg"))  return "audio/ogg";
        if (lower.endsWith(".opus")) return "audio/ogg; codecs=opus";
        if (lower.endsWith(".m4a"))  return "audio/mp4";
        if (lower.endsWith(".aac"))  return "audio/aac";
        if (lower.endsWith(".wav"))  return "audio/wav";
        if (lower.endsWith(".wma"))  return "audio/x-ms-wma";
        // Fallback to JVM probe
        String mime = URLConnection.guessContentTypeFromName(path);
        return mime != null ? mime : "application/octet-stream";
    }

    private static String resolveDir(String path) {
        // Expand leading ~ manually since Java doesn't do it
        if (path.startsWith("~/") || path.equals("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}