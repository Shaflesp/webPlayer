package MPD;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Thin MPD protocol client over a plain TCP socket.
 * Each instance represents one connection; close it when done.
 * <p>
 * MPD protocol basics:
 *   - Connect → server sends  "OK MPD <version>"
 *   - Send a command line     → server sends data lines ending with "OK"
 *   - On error                → server sends "ACK [code@cmd] {name} message"
 */
public class MPDClient implements Closeable {

    private static final String DEFAULT_HOST = "localhost";
    private static final int    DEFAULT_PORT = 6600;
    private static final int    TIMEOUT_MS   = 10_000;

    private final Socket       socket;
    private final BufferedReader reader;
    private final PrintWriter    writer;

    // ── Constructors ──────────────────────────────────────────────────────────

    public MPDClient() throws IOException {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public MPDClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(TIMEOUT_MS);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        String greeting = reader.readLine();
        if (greeting == null || !greeting.startsWith("OK MPD")) {
            throw new IOException("Unexpected MPD greeting: " + greeting);
        }
    }

    // ── Core send/receive ─────────────────────────────────────────────────────

    /**
     * Send one command; return all response lines (without the trailing "OK").
     * Throws IOException on ACK (MPD-level error).
     */
    public synchronized List<String> command(String cmd) throws IOException {
        writer.println(cmd);
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals("OK")) break;
            if (line.startsWith("ACK ")) throw new IOException(line);
            lines.add(line);
        }
        return lines;
    }

    /**
     * Send command and parse "Key: Value" pairs into a single LinkedHashMap.
     * Suitable for commands that return a single "object": status, currentsong.
     */
    public Map<String, String> commandAsMap(String cmd) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : command(cmd)) {
            int colon = line.indexOf(": ");
            if (colon > 0) map.put(line.substring(0, colon), line.substring(colon + 2));
        }
        return map;
    }

    /**
     * Send command and parse response into a list of maps, splitting on a given key.
     * Suitable for: playlistinfo (split on "file"), search (split on "file").
     */
    public List<Map<String, String>> commandAsBlocks(String cmd, String splitKey) throws IOException {
        List<Map<String, String>> result  = new ArrayList<>();
        Map<String, String>       current = null;

        for (String line : command(cmd)) {
            int colon = line.indexOf(": ");
            if (colon <= 0) continue;
            String key = line.substring(0, colon);
            String val = line.substring(colon + 2);

            if (key.equals(splitKey)) {
                if (current != null) result.add(current);
                current = new LinkedHashMap<>();
            }
            if (current == null) current = new LinkedHashMap<>();
            current.put(key, val);
        }
        if (current != null && !current.isEmpty()) result.add(current);
        return result;
    }

    /**
     * Parse lsinfo response into blocks, splitting on any of: file, directory, playlist.
     */
    public List<Map<String, String>> commandAsLsBlocks(String cmd) throws IOException {
        List<Map<String, String>> result    = new ArrayList<>();
        Map<String, String>       current   = null;
        Set<String>               starters  = Set.of("file", "directory", "playlist");

        for (String line : command(cmd)) {
            int colon = line.indexOf(": ");
            if (colon <= 0) continue;
            String key = line.substring(0, colon);
            String val = line.substring(colon + 2);

            if (starters.contains(key)) {
                if (current != null) result.add(current);
                current = new LinkedHashMap<>();
                current.put("_type", key);   // synthetic type field
            }
            if (current == null) current = new LinkedHashMap<>();
            current.put(key, val);
        }
        if (current != null && !current.isEmpty()) result.add(current);
        return result;
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    /** Escape a string for use inside MPD double-quoted arguments. */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}