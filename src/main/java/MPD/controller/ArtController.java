package MPD.controller;

import MPD.config.AppSettings;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@RestController
@RequestMapping("/ArtServlet")
public class ArtController {

    private static final String[] COVER_NAMES =
            { "cover.jpg", "cover.png", "folder.jpg", "folder.png", "artwork.jpg", "front.jpg" };

    private final AppSettings settings;

    public ArtController(AppSettings settings) { this.settings = settings; }

    @GetMapping
    public ResponseEntity<byte[]> art(@RequestParam String uri) {
        Path base = Path.of(AppSettings.expandHome(settings.get("music.dir"))).normalize();
        Path song = base.resolve(uri).normalize();

        if (!song.startsWith(base))
            return ResponseEntity.badRequest().build();

        // 1. Look for a cover file alongside the song
        Path dir = song.getParent();
        if (dir != null) {
            for (String name : COVER_NAMES) {
                Path cover = dir.resolve(name);
                if (Files.exists(cover)) {
                    try {
                        return ok(Files.readAllBytes(cover), sniffMime(name));
                    } catch (IOException ignored) {}
                }
            }
        }

        // 2. MPD binary protocol — readpicture (embedded), then albumart
        byte[] data = fetchMpdArt(uri, "readpicture");
        if (data == null || data.length == 0) data = fetchMpdArt(uri, "albumart");
        if (data != null && data.length > 0)  return ok(data, "image/jpeg");

        return ResponseEntity.notFound().build();
    }

    // ── MPD binary art protocol ───────────────────────────────────────────────

    /**
     * Reads album art from MPD via the readpicture / albumart binary commands.
     * The response may span multiple chunks when the image exceeds MPD's buffer
     * size (~64 KB by default).  The protocol per chunk is:
     *
     *   size: TOTAL\n
     *   type: MIME\n
     *   binary: N\n
     *   <N raw bytes>
     *   \nOK\n
     *
     * Subsequent requests use an increasing byte offset.
     */
    private byte[] fetchMpdArt(String songUri, String command) {
        try (Socket   socket = new Socket(settings.get("mpd.host"), settings.getInt("mpd.port"));
             InputStream  in = socket.getInputStream();
             OutputStream out= socket.getOutputStream()) {

            readLine(in); // consume MPD greeting "OK MPD x.y.z"

            final String esc = songUri.replace("\\", "\\\\").replace("\"", "\\\"");

            // ── First chunk (offset = 0) ──────────────────────────────────────
            send(out, command + " \"" + esc + "\" 0");

            int totalSize = 0, chunkSize = 0;
            String line;
            while ((line = readLine(in)) != null) {
                if (line.startsWith("ACK") || line.equals("OK")) return null;
                if (line.startsWith("size: "))   totalSize = Integer.parseInt(line.substring(6).trim());
                if (line.startsWith("binary: ")) { chunkSize = Integer.parseInt(line.substring(8).trim()); break; }
            }
            if (chunkSize == 0 || totalSize == 0) return null;

            ByteArrayOutputStream buf = new ByteArrayOutputStream(totalSize);
            buf.write(in.readNBytes(chunkSize));
            int offset = chunkSize;

            // ── Remaining chunks ──────────────────────────────────────────────
            while (offset < totalSize) {
                // After each chunk: "\n" then "OK\n" — consume both before sending next request
                readLine(in); // empty line (the \n separator after binary data)
                readLine(in); // "OK"

                send(out, command + " \"" + esc + "\" " + offset);

                // Parse headers of the next chunk response
                chunkSize = 0;
                while ((line = readLine(in)) != null) {
                    if (line.startsWith("ACK") || line.equals("OK")) break;
                    if (line.startsWith("binary: ")) { chunkSize = Integer.parseInt(line.substring(8).trim()); break; }
                }
                if (chunkSize == 0) break;

                buf.write(in.readNBytes(chunkSize));
                offset += chunkSize;
            }

            return buf.size() > 0 ? buf.toByteArray() : null;

        } catch (Exception ignored) { return null; }
    }

    private static void send(OutputStream out, String cmd) throws IOException {
        out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            sb.append((char) c);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static ResponseEntity<byte[]> ok(byte[] data, String mime) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)))
                .body(data);
    }

    private static String sniffMime(String name) {
        return name.endsWith(".png") ? "image/png" : "image/jpeg";
    }
}