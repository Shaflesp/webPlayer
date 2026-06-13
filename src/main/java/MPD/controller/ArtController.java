package MPD.controller;

import MPD.config.AppSettings;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.Socket;
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

        // Path traversal guard
        if (!song.startsWith(base))
            return ResponseEntity.badRequest().build();

        // 1. Filesystem cover file in the song's directory
        Path dir = song.getParent();
        if (dir != null) {
            for (String name : COVER_NAMES) {
                Path cover = dir.resolve(name);
                if (Files.exists(cover)) {
                    try {
                        byte[] data = Files.readAllBytes(cover);
                        return ok(data, sniffMime(name));
                    } catch (IOException ignored) {}
                }
            }
        }

        // 2. MPD binary protocol (readpicture then albumart)
        byte[] data = fetchMpdArt(uri, "readpicture");
        if (data == null || data.length == 0) data = fetchMpdArt(uri, "albumart");
        if (data != null && data.length > 0) return ok(data, "image/jpeg");

        return ResponseEntity.notFound().build();
    }

    private byte[] fetchMpdArt(String songUri, String command) {
        try (Socket socket = new Socket(settings.get("mpd.host"), settings.getInt("mpd.port"));
             InputStream  in  = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            // Consume MPD greeting
            readLine(in);

            String cmd = command + " \"" + songUri + "\" 0\n";
            out.write(cmd.getBytes());
            out.flush();

            // Parse response header
            String firstLine = readLine(in);
            if (firstLine == null || firstLine.startsWith("ACK")) return null;

            int size = 0;
            String binaryLine = null;
            String line = firstLine;
            while (line != null && !line.equals("OK")) {
                if (line.startsWith("size: "))   size = Integer.parseInt(line.substring(6).trim());
                if (line.startsWith("binary: ")) binaryLine = line;
                if (binaryLine != null) break;
                line = readLine(in);
            }
            if (binaryLine == null || size == 0) return null;

            int chunkSize = Integer.parseInt(binaryLine.substring(8).trim());
            byte[] chunk = in.readNBytes(chunkSize);

            // Reassemble full image using chunked reads
            ByteArrayOutputStream buf = new ByteArrayOutputStream(size);
            buf.write(chunk);
            int offset = chunkSize;
            while (offset < size) {
                readLine(in); // "OK"
                String nextCmd = command + " \"" + songUri + "\" " + offset + "\n";
                out.write(nextCmd.getBytes()); out.flush();
                readLine(in); // skip "binary: N"
                int remaining = Math.min(size - offset, 65536);
                byte[] part = in.readNBytes(remaining);
                buf.write(part);
                offset += part.length;
            }
            return buf.toByteArray();

        } catch (Exception ignored) { return null; }
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
