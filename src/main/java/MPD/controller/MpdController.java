package MPD.controller;

import MPD.MPDClient;
import MPD.service.MpdService;
import com.google.gson.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/MPDServlet")
public class MpdController {

    private static final int SEARCH_LIMIT = 200;
    private final MpdService mpd;
    private static final Gson GSON = new GsonBuilder().create();

    public MpdController(MpdService mpd) { this.mpd = mpd; }

    // ── GET ───────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<JsonElement> get(@RequestParam String action,
                                           @RequestParam(required = false) String uri,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String name)
            throws Exception {
        try (MPDClient client = mpd.connect()) {
            JsonElement result = switch (action) {
                case "nowplaying"    -> nowPlaying(client);
                case "status"        -> GSON.toJsonTree(client.commandAsMap("status"));
                case "currentsong"   -> GSON.toJsonTree(client.commandAsMap("currentsong"));
                case "queue"         -> playlistInfo(client);
                case "browse"        -> browse(client, uri != null ? uri : "");
                case "search"        -> search(client, q != null ? q : "");
                case "playlists"     -> playlists(client);
                case "playlistsongs" -> playlistSongs(client, name);
                default              -> throw new IllegalArgumentException("Unknown action: " + action);
            };
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    // ── POST ──────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> post(@RequestBody Map<String, Object> body)
            throws Exception {
        String action = (String) body.getOrDefault("action", "");
        try (MPDClient client = mpd.connect()) {
            switch (action) {
                case "play"           -> client.command("play");
                case "pause"          -> client.command("pause 1");
                case "resume"         -> client.command("pause 0");
                case "stop"           -> client.command("stop");
                case "next"           -> client.command("next");
                case "previous"       -> client.command("previous");
                case "playid"         -> client.command("playid "  + toInt(body.get("id")));
                case "delete"         -> client.command("delete "  + toInt(body.get("pos")));
                case "clear"          -> client.command("clear");
                case "update"         -> client.command("update");
                case "seek"           -> client.command("seekcur " + toDouble(body.get("time")));
                case "setvol"         -> client.command("setvol "  + toInt(body.get("volume")));
                case "add"            -> client.command("add \""   + body.get("uri") + "\"");
                case "addplay"        -> {
                    client.command("add \"" + body.get("uri") + "\"");
                    Map<String, String> st = client.commandAsMap("status");
                    int len = Integer.parseInt(st.getOrDefault("playlistlength", "1"));
                    client.command("play " + (len - 1));
                }
                case "move"           -> client.command("move "  + toInt(body.get("from")) + " " + toInt(body.get("to")));
                case "toggle_random"  -> {
                    Map<String, String> st = client.commandAsMap("status");
                    client.command("random " + (st.getOrDefault("random", "0").equals("1") ? "0" : "1"));
                }
                case "toggle_repeat"  -> {
                    Map<String, String> st = client.commandAsMap("status");
                    client.command("repeat " + (st.getOrDefault("repeat", "0").equals("1") ? "0" : "1"));
                }
                case "toggle_single"  -> {
                    Map<String, String> st = client.commandAsMap("status");
                    client.command("single " + (st.getOrDefault("single", "0").equals("1") ? "0" : "1"));
                }
                case "playlistload"   -> client.command("load \"" + MPDClient.escape((String) body.get("name")) + "\"");
                case "playlistsave"   -> {
                    String esc = MPDClient.escape((String) body.get("name"));
                    // "save" fails if a playlist with this name already exists on
                    // older MPD versions (no overwrite mode) — rm-then-save gives
                    // idempotent create-or-overwrite behaviour on any MPD version.
                    try { client.command("rm \"" + esc + "\""); } catch (Exception ignored) {}
                    client.command("save \"" + esc + "\"");
                }
                case "playlistrm"     -> client.command("rm \"" + MPDClient.escape((String) body.get("name")) + "\"");
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Type coercion helpers ─────────────────────────────────────────────────

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(v));
    }

    private static String toDouble(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JsonObject nowPlaying(MPDClient client) throws Exception {
        Map<String, String> status = client.commandAsMap("status");
        Map<String, String> song   = client.commandAsMap("currentsong");
        JsonObject obj = new JsonObject();
        obj.add("status", toStatusJson(status));
        obj.add("song",   GSON.toJsonTree(song));
        return obj;
    }

    private JsonObject toStatusJson(Map<String, String> m) {
        JsonObject s = new JsonObject();
        s.addProperty("state",           m.getOrDefault("state",           "stop"));
        s.addProperty("elapsed",         parseDouble(m.get("elapsed")));
        s.addProperty("duration",        parseDouble(m.get("duration")));
        s.addProperty("volume",          parseInt(m.get("volume")));
        s.addProperty("random",          parseInt(m.get("random")));
        s.addProperty("repeat",          parseInt(m.get("repeat")));
        s.addProperty("single",          parseInt(m.get("single")));
        s.addProperty("consume",         parseInt(m.get("consume")));
        s.addProperty("songid",          parseInt(m.get("songid")));
        s.addProperty("playlistlength",  parseInt(m.get("playlistlength")));
        s.addProperty("playlistversion", parseInt(m.get("playlist")));
        s.addProperty("bitrate",         parseInt(m.get("bitrate")));
        return s;
    }

    private JsonArray playlistInfo(MPDClient client) throws Exception {
        List<Map<String, String>> songs = client.commandAsBlocks("playlistinfo", "file");
        return GSON.toJsonTree(songs).getAsJsonArray();
    }

    private JsonArray browse(MPDClient client, String uri) throws Exception {
        String cmd = uri.isEmpty() ? "lsinfo" : "lsinfo \"" + MPDClient.escape(uri) + "\"";
        List<Map<String, String>> items = client.commandAsLsBlocks(cmd);
        JsonArray arr = new JsonArray();
        for (Map<String, String> item : items) arr.add(GSON.toJsonTree(item));
        return arr;
    }

    private JsonArray search(MPDClient client, String q) throws Exception {
        if (q.isBlank()) return new JsonArray();
        List<Map<String, String>> results =
                client.commandAsBlocks("search any \"" + MPDClient.escape(q) + "\"", "file");
        JsonArray arr = new JsonArray();
        int i = 0;
        for (Map<String, String> r : results) {
            if (i++ >= SEARCH_LIMIT) break;
            arr.add(GSON.toJsonTree(r));
        }
        return arr;
    }

    /** MPD's listplaylists returns repeating "playlist: NAME" / "Last-Modified: ..." blocks. */
    private JsonArray playlists(MPDClient client) throws Exception {
        List<Map<String, String>> entries = client.commandAsBlocks("listplaylists", "playlist");
        return GSON.toJsonTree(entries).getAsJsonArray();
    }

    /** MPD's listplaylistinfo NAME returns full song blocks, same shape as playlistinfo. */
    private JsonArray playlistSongs(MPDClient client, String name) throws Exception {
        if (name == null || name.isBlank()) return new JsonArray();
        List<Map<String, String>> songs = client.commandAsBlocks(
                "listplaylistinfo \"" + MPDClient.escape(name) + "\"", "file");
        return GSON.toJsonTree(songs).getAsJsonArray();
    }

    private static double parseDouble(String v) {
        if (v == null) return 0;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return 0; }
    }
    private static int parseInt(String v) {
        if (v == null) return 0;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }
}