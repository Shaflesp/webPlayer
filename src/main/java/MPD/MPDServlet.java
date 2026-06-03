package MPD;

import com.google.gson.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.*;
import java.util.*;

/**
 * REST bridge to MPD.
 *
 * GET  /MPDServlet?action=<name>[&params]   → JSON data
 * POST /MPDServlet  body: { "action": "...", ...params }  → { "ok": true }
 *
 * ── GET actions ──────────────────────────────────────────────────────────────
 *   nowplaying  → { status:{…}, song:{…} }
 *   status      → MPD status map
 *   currentsong → current song tags
 *   queue       → array of queue items
 *   search?q=   → search results (max 200)
 *   browse?uri= → directory listing
 *
 * ── POST actions ─────────────────────────────────────────────────────────────
 *   play, pause, resume, toggle, stop, next, previous
 *   playid      { id: int }
 *   setvol      { volume: int 0-100 }
 *   seek        { time: double seconds }
 *   add         { uri: string }
 *   addplay     { uri: string }     add + immediately play
 *   delete      { pos: int }
 *   clear
 *   toggle_random, toggle_repeat, toggle_single
 */
@WebServlet("/MPDServlet")
public class MPDServlet extends HttpServlet {

    private static final int SEARCH_LIMIT = 200;

    private static final Gson GSON = new GsonBuilder().create();

    // ── GET ───────────────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        setCORSHeaders(res);

        String action = req.getParameter("action");
        if (action == null) action = "nowplaying";

        try (MPDClient mpd = new MPDClient(AppConfig.get("mpd.host"), AppConfig.getInt("mpd.port"))) {
            JsonElement result = switch (action) {
                case "nowplaying"  -> nowPlaying(mpd);
                case "status"      -> mapToJson(mpd.commandAsMap("status"));
                case "currentsong" -> mapToJson(mpd.commandAsMap("currentsong"));
                case "queue"       -> queue(mpd);
                case "search"      -> search(mpd, req.getParameter("q"));
                case "browse"      -> browse(mpd, req.getParameter("uri"));
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            };
            res.getWriter().write(GSON.toJson(result));

        } catch (Exception e) {
            sendError(res, e);
        }
    }

    // ── POST ──────────────────────────────────────────────────────────────────

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        setCORSHeaders(res);

        req.setCharacterEncoding("UTF-8");
        JsonObject body   = GSON.fromJson(req.getReader(), JsonObject.class);
        String     action = body.has("action") ? body.get("action").getAsString() : "";

        JsonObject result = new JsonObject();

        try (MPDClient mpd = new MPDClient(AppConfig.get("mpd.host"), AppConfig.getInt("mpd.port"))) {
            switch (action) {
                case "play"           -> mpd.command("play");
                case "pause"          -> mpd.command("pause 1");
                case "resume"         -> mpd.command("pause 0");
                case "toggle"         -> mpd.command("pause");
                case "stop"           -> mpd.command("stop");
                case "next"           -> mpd.command("next");
                case "previous"       -> mpd.command("previous");

                case "playid"         -> {
                    int id = body.get("id").getAsInt();
                    mpd.command("playid " + id);
                }
                case "setvol"         -> {
                    int vol = Math.max(0, Math.min(100, body.get("volume").getAsInt()));
                    mpd.command("setvol " + vol);
                }
                case "seek"           -> {
                    double t = body.get("time").getAsDouble();
                    mpd.command("seekcur " + t);
                }
                case "add"            -> {
                    String uri = body.get("uri").getAsString();
                    mpd.command("add \"" + MPDClient.escape(uri) + "\"");
                }
                case "addplay"        -> {
                    String uri = body.get("uri").getAsString();
                    List<String> r = mpd.command("addid \"" + MPDClient.escape(uri) + "\"");
                    String songId = r.stream()
                            .filter(l -> l.startsWith("Id: "))
                            .map(l -> l.substring(4).trim())
                            .findFirst().orElse("0");
                    mpd.command("playid " + songId);
                }
                case "delete"         -> {
                    int pos = body.get("pos").getAsInt();
                    mpd.command("delete " + pos);
                }
                case "clear"          -> mpd.command("clear");

                case "toggle_random"  -> {
                    Map<String, String> st = mpd.commandAsMap("status");
                    int cur = parseInt(st.get("random"));
                    mpd.command("random " + (cur == 0 ? 1 : 0));
                }
                case "toggle_repeat"  -> {
                    Map<String, String> st = mpd.commandAsMap("status");
                    int cur = parseInt(st.get("repeat"));
                    mpd.command("repeat " + (cur == 0 ? 1 : 0));
                }
                case "toggle_single"  -> {
                    Map<String, String> st = mpd.commandAsMap("status");
                    int cur = parseInt(st.get("single"));
                    mpd.command("single " + (cur == 0 ? 1 : 0));
                }

                // Trigger an MPD database rescan (same as mpc update)
                case "update"         -> mpd.command("update");
                case "rescan"         -> mpd.command("rescan");   // full rescan ignoring mtime

                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            result.addProperty("ok", true);

        } catch (Exception e) {
            result.addProperty("ok", false);
            result.addProperty("error", e.getMessage());
        }

        res.getWriter().write(GSON.toJson(result));
    }

    // ── GET helpers ───────────────────────────────────────────────────────────

    /** Combined status + currentsong in a single MPD connection. */
    private JsonObject nowPlaying(MPDClient mpd) throws IOException {
        JsonObject obj = new JsonObject();
        obj.add("status", buildStatus(mpd.commandAsMap("status")));
        obj.add("song",   mapToJson(mpd.commandAsMap("currentsong")));
        return obj;
    }

    private JsonObject buildStatus(Map<String, String> m) {
        JsonObject o = new JsonObject();
        o.addProperty("state",          m.getOrDefault("state",  "stop"));
        o.addProperty("volume",         parseInt(m.get("volume")));
        o.addProperty("random",         parseInt(m.get("random")));
        o.addProperty("repeat",         parseInt(m.get("repeat")));
        o.addProperty("single",         parseInt(m.get("single")));
        o.addProperty("consume",        parseInt(m.get("consume")));
        o.addProperty("song",           parseInt(m.get("song")));
        o.addProperty("songid",         parseInt(m.get("songid")));
        o.addProperty("elapsed",        parseDouble(m.get("elapsed")));
        o.addProperty("duration",       parseDouble(m.get("duration")));
        o.addProperty("playlistlength", parseInt(m.get("playlistlength")));
        o.addProperty("bitrate",        parseInt(m.get("bitrate")));
        return o;
    }

    private JsonArray queue(MPDClient mpd) throws IOException {
        List<Map<String, String>> blocks = mpd.commandAsBlocks("playlistinfo", "file");
        JsonArray arr = new JsonArray();
        for (Map<String, String> b : blocks) arr.add(mapToJson(b));
        return arr;
    }

    private JsonArray search(MPDClient mpd, String query) throws IOException {
        if (query == null || query.isBlank()) return new JsonArray();
        String escaped = MPDClient.escape(query);
        // "window" limits results (MPD >= 0.22); falls back gracefully on older versions
        String cmd = String.format("search any \"%s\" window 0:%d", escaped, SEARCH_LIMIT);
        List<Map<String, String>> blocks;
        try {
            blocks = mpd.commandAsBlocks(cmd, "file");
        } catch (IOException e) {
            // Fallback: without window param
            blocks = mpd.commandAsBlocks("search any \"" + escaped + "\"", "file");
        }
        JsonArray arr = new JsonArray();
        for (Map<String, String> b : blocks) arr.add(mapToJson(b));
        return arr;
    }

    private JsonArray browse(MPDClient mpd, String uri) throws IOException {
        String cmd = (uri == null || uri.isBlank())
                ? "lsinfo"
                : "lsinfo \"" + MPDClient.escape(uri) + "\"";

        List<Map<String, String>> blocks = mpd.commandAsLsBlocks(cmd);
        JsonArray arr = new JsonArray();
        for (Map<String, String> b : blocks) arr.add(mapToJson(b));
        return arr;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private JsonObject mapToJson(Map<String, String> map) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }

    private int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private double parseDouble(String s) {
        if (s == null) return 0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private void setCORSHeaders(HttpServletResponse res) {
        res.setHeader("Access-Control-Allow-Origin", "*");
        res.setHeader("Cache-Control", "no-cache");
    }

    private void sendError(HttpServletResponse res, Exception e) throws IOException {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        JsonObject err = new JsonObject();
        err.addProperty("error", e.getMessage());
        res.getWriter().write(GSON.toJson(err));
        e.printStackTrace();
    }
}