package MPD.controller;

import MPD.MPDClient;
import MPD.service.MpdService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST bridge for all MPD commands.
 * Returns plain Java Maps/Lists — serialized by GsonHttpMessageConverter.
 */
@RestController
@RequestMapping("/MPDServlet")
public class MpdController {

    private static final int SEARCH_LIMIT = 200;
    private final MpdService mpd;

    public MpdController(MpdService mpd) { this.mpd = mpd; }

    // ── GET ───────────────────────────────────────────────────────────────────

    @GetMapping
    public Object get(
            @RequestParam String action,
            @RequestParam(required = false) String uri,
            @RequestParam(required = false) String q) throws Exception {

        try (MPDClient client = mpd.connect()) {
            return switch (action) {
                case "nowplaying"  -> nowPlaying(client);
                case "status"      -> toStatusMap(client.commandAsMap("status"));
                case "currentsong" -> client.commandAsMap("currentsong");
                case "queue"       -> client.commandAsBlocks("playlistinfo", "file");
                case "browse"      -> client.commandAsLsBlocks(
                                        "lsinfo" + (uri != null && !uri.isBlank()
                                            ? " \"" + MPDClient.escape(uri) + "\""
                                            : ""));
                case "search"      -> search(client, q != null ? q : "");
                default            -> ResponseEntity.badRequest()
                                        .body(Map.of("error", "Unknown action: " + action));
            };
        }
    }

    // ── POST ──────────────────────────────────────────────────────────────────

    @PostMapping
    public Map<String, Object> post(@RequestBody Map<String, Object> body) throws Exception {
        String action = (String) body.getOrDefault("action", "");
        try (MPDClient client = mpd.connect()) {
            switch (action) {
                case "play"          -> client.command("play");
                case "pause"         -> client.command("pause 1");
                case "resume"        -> client.command("pause 0");
                case "stop"          -> client.command("stop");
                case "next"          -> client.command("next");
                case "previous"      -> client.command("previous");
                case "playid"        -> client.command("playid "  + toInt(body.get("id")));
                case "delete"        -> client.command("delete "  + toInt(body.get("pos")));
                case "clear"         -> client.command("clear");
                case "update"        -> client.command("update");
                case "seek"          -> client.command("seekcur " + toTime(body.get("time")));
                case "setvol"        -> client.command("setvol "  + toInt(body.get("volume")));
                case "add"           -> client.command("add \""   + MPDClient.escape(str(body.get("uri"))) + "\"");
                case "addplay"       -> {
                    client.command("add \"" + MPDClient.escape(str(body.get("uri"))) + "\"");
                    int len = parseInt(client.commandAsMap("status").get("playlistlength"));
                    client.command("play " + Math.max(0, len - 1));
                }
                case "toggle_random" -> {
                    String v = client.commandAsMap("status").getOrDefault("random", "0");
                    client.command("random " + (v.equals("1") ? "0" : "1"));
                }
                case "toggle_repeat" -> {
                    String v = client.commandAsMap("status").getOrDefault("repeat", "0");
                    client.command("repeat " + (v.equals("1") ? "0" : "1"));
                }
                case "toggle_single" -> {
                    String v = client.commandAsMap("status").getOrDefault("single", "0");
                    client.command("single " + (v.equals("1") ? "0" : "1"));
                }
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            return Map.of("ok", true);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> nowPlaying(MPDClient client) throws Exception {
        Map<String, String> status = client.commandAsMap("status");
        Map<String, String> song   = client.commandAsMap("currentsong");
        Map<String, Object> out    = new LinkedHashMap<>();
        out.put("status", toStatusMap(status));
        out.put("song",   song);
        return out;
    }

    private Map<String, Object> toStatusMap(Map<String, String> m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("state",           m.getOrDefault("state", "stop"));
        s.put("elapsed",         parseDouble(m.get("elapsed")));
        s.put("duration",        parseDouble(m.get("duration")));
        s.put("volume",          parseInt(m.get("volume")));
        s.put("random",          parseInt(m.get("random")));
        s.put("repeat",          parseInt(m.get("repeat")));
        s.put("single",          parseInt(m.get("single")));
        s.put("consume",         parseInt(m.get("consume")));
        s.put("songid",          parseInt(m.get("songid")));
        s.put("playlistlength",  parseInt(m.get("playlistlength")));
        s.put("playlistversion", parseInt(m.get("playlist")));   // MPD calls it "playlist"
        s.put("bitrate",         parseInt(m.get("bitrate")));
        return s;
    }

    private List<Map<String, String>> search(MPDClient client, String q) throws Exception {
        if (q.isBlank()) return List.of();
        List<Map<String, String>> results =
            client.commandAsBlocks("search any \"" + MPDClient.escape(q) + "\"", "file");
        return results.size() > SEARCH_LIMIT ? results.subList(0, SEARCH_LIMIT) : results;
    }

    // ── Type helpers (Gson deserialises all JSON numbers as Double) ────────────

    private static int    toInt(Object v)  { return v instanceof Number n ? n.intValue()    : Integer.parseInt(String.valueOf(v)); }
    private static int    parseInt(String v) { try { return v == null ? 0 : Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; } }
    private static double parseDouble(String v) { try { return v == null ? 0 : Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return 0; } }
    private static String toTime(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v);
    }
    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
}
