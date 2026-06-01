package MPD;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * Serves album art for a given song URI.
 *
 * GET /ArtServlet?uri=relative/path/to/song.flac
 *
 * Resolution order:
 *   1. cover.jpg / folder.jpg / front.png … in the same directory (fast path)
 *   2. MPD readpicture command  (embedded tags, MPD ≥ 0.22)
 *   3. MPD albumart command     (embedded tags, MPD ≥ 0.21, older fallback)
 *   4. HTTP 404
 *
 * Responses carry ETag + Cache-Control: max-age=86400 so the browser
 * only fetches once per track per day.
 */
@WebServlet("/ArtServlet")
public class ArtServlet extends HttpServlet {

    // ── Config ────────────────────────────────────────────────────────────────
    private static final String MUSIC_DIR;
    private static final String MPD_HOST = "localhost";
    private static final int    MPD_PORT = 6600;

    static {
        String dir = System.getenv("MPD_MUSIC_DIR");
        if (dir == null || dir.isBlank())
            dir = System.getProperty("user.home") + "/Music";
        if (dir.startsWith("~/") || dir.equals("~"))
            dir = System.getProperty("user.home") + dir.substring(1);
        MUSIC_DIR = dir;
    }

    // Cover art filenames to probe (case-insensitive, filesystem-first)
    private static final List<String> STEMS = List.of(
            "cover", "folder", "front", "album", "artwork", "albumart"
    );
    private static final List<String> EXTS = List.of(
            ".jpg", ".jpeg", ".png", ".webp"
    );

    // ── GET ───────────────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String uri = req.getParameter("uri");
        if (uri == null || uri.isBlank()) { res.sendError(400); return; }

        // Path traversal guard
        Path base = Path.of(MUSIC_DIR).normalize();
        Path song = base.resolve(uri).normalize();
        if (!song.startsWith(base)) { res.sendError(403); return; }

        // Simple ETag based on URI for browser caching
        String etag = "\"art-" + Integer.toHexString(uri.hashCode()) + "\"";
        if (etag.equals(req.getHeader("If-None-Match"))) {
            res.setStatus(304);
            return;
        }
        res.setHeader("ETag", etag);
        res.setHeader("Cache-Control", "max-age=86400");

        // ── 1. Filesystem cover files ─────────────────────────────────────────
        Path cover = findCoverFile(song.getParent());
        if (cover != null) {
            serveFile(res, cover);
            return;
        }

        // ── 2 & 3. MPD embedded art ───────────────────────────────────────────
        byte[] art = fetchMpdArt(uri, "readpicture");
        if (art == null) art = fetchMpdArt(uri, "albumart");
        if (art != null && art.length > 0) {
            res.setContentType(sniffMime(art));
            res.setContentLength(art.length);
            res.getOutputStream().write(art);
            return;
        }

        res.sendError(404);
    }

    // ── Filesystem cover search ───────────────────────────────────────────────

    private Path findCoverFile(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path entry : ds) {
                if (!Files.isRegularFile(entry)) continue;
                String name = entry.getFileName().toString().toLowerCase();
                for (String stem : STEMS)
                    for (String ext : EXTS)
                        if (name.equals(stem + ext)) return entry;
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void serveFile(HttpServletResponse res, Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        res.setContentType(
                name.endsWith(".png")  ? "image/png"  :
                        name.endsWith(".webp") ? "image/webp" : "image/jpeg"
        );
        res.setContentLengthLong(Files.size(file));
        Files.copy(file, res.getOutputStream());
    }

    // ── MPD binary art protocol ───────────────────────────────────────────────
    /*
     * MPD mixes text headers and raw binary in one stream:
     *
     *   CLIENT: albumart "path/song.flac" 0\n
     *   SERVER: size: 123456\n          ← optional (readpicture adds type: too)
     *           binary: 65536\n
     *           <exactly 65536 bytes of raw image data>
     *           \n
     *           OK\n
     *
     * We must NOT use BufferedReader here — it would read ahead past the
     * binary boundary. All reading is byte-by-byte until we know the chunk
     * size, then readFully for the binary block.
     */
    private byte[] fetchMpdArt(String songUri, String cmd) {
        try (Socket socket = new Socket(MPD_HOST, MPD_PORT)) {
            socket.setSoTimeout(5_000);
            InputStream  in  = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Consume MPD greeting
            String greeting = readLine(in);
            if (greeting == null || !greeting.startsWith("OK MPD")) return null;

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int offset    = 0;
            int totalSize = -1;

            while (true) {
                // Send command with current offset
                String request = cmd + " \"" + MPDClient.escape(songUri) + "\" " + offset + "\n";
                out.write(request.getBytes(StandardCharsets.UTF_8));
                out.flush();

                // Read header lines until "binary: N", "ACK", or unexpected EOF
                int chunkSize = -1;
                while (true) {
                    String line = readLine(in);
                    if (line == null || line.startsWith("ACK"))
                        return buf.size() > 0 ? buf.toByteArray() : null;
                    if (line.startsWith("size: ") && totalSize < 0)
                        totalSize = Integer.parseInt(line.substring(6).trim());
                    else if (line.startsWith("binary: ")) {
                        chunkSize = Integer.parseInt(line.substring(8).trim());
                        break;
                    }
                    // "type: image/jpeg" and other fields are silently ignored
                }

                if (chunkSize <= 0) break;   // no more data

                // Read exactly chunkSize bytes of binary
                byte[] chunk = new byte[chunkSize];
                int got = 0;
                while (got < chunkSize) {
                    int n = in.read(chunk, got, chunkSize - got);
                    if (n == -1) break;
                    got += n;
                }
                buf.write(chunk, 0, got);

                in.read();       // consume the '\n' that follows the binary block
                readLine(in);    // consume "OK"

                offset += got;
                if (totalSize > 0 && offset >= totalSize) break;
            }

            return buf.size() > 0 ? buf.toByteArray() : null;

        } catch (Exception e) {
            return null;   // command unsupported or connection issue — caller tries next strategy
        }
    }

    /** Read one text line byte-by-byte (safe to use immediately before binary data). */
    private String readLine(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    // ── MIME sniffing ─────────────────────────────────────────────────────────

    private String sniffMime(byte[] d) {
        if (d.length >= 3 && d[0] == (byte)0xFF && d[1] == (byte)0xD8) return "image/jpeg";
        if (d.length >= 4 && d[0] == (byte)0x89 && d[1] == 'P')        return "image/png";
        if (d.length >= 4 && d[0] == 'R' && d[1] == 'I')               return "image/webp";
        if (d.length >= 3 && d[0] == 'G' && d[1] == 'I')               return "image/gif";
        return "image/jpeg";
    }
}