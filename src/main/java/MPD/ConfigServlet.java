package MPD;

import com.google.gson.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.*;
import java.util.*;

/**
 * Settings REST endpoint.
 *
 * GET  /ConfigServlet            → { all key/value pairs }
 * GET  /ConfigServlet?action=test[&host=x&port=y]  → { ok, state }
 * POST /ConfigServlet  body: { key: value, … }  → { ok }
 */
@WebServlet("/ConfigServlet")
public class ConfigServlet extends HttpServlet {

    private static final Gson GSON = new GsonBuilder().create();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json;charset=UTF-8");
        res.setHeader("Cache-Control", "no-cache");

        String action = req.getParameter("action");

        if ("test".equals(action)) {
            // Use posted host/port or fall back to saved values
            String host = nvl(req.getParameter("host"), AppConfig.get("mpd.host"));
            int    port = parsePort(req.getParameter("port"));

            JsonObject result = new JsonObject();
            try (MPDClient mpd = new MPDClient(host, port)) {
                Map<String, String> st = mpd.commandAsMap("status");
                result.addProperty("ok",    true);
                result.addProperty("state", st.getOrDefault("state", "unknown"));
            } catch (Exception e) {
                result.addProperty("ok",    false);
                result.addProperty("error", e.getMessage());
            }
            res.getWriter().write(GSON.toJson(result));

        } else {
            // Return all settings
            res.getWriter().write(GSON.toJson(AppConfig.getAll()));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json;charset=UTF-8");
        req.setCharacterEncoding("UTF-8");

        JsonObject body = GSON.fromJson(req.getReader(), JsonObject.class);
        Map<String, String> updates = new LinkedHashMap<>();
        for (var e : body.entrySet())
            updates.put(e.getKey(), e.getValue().getAsString());

        JsonObject result = new JsonObject();
        try {
            AppConfig.saveAll(updates);
            result.addProperty("ok", true);
        } catch (Exception e) {
            result.addProperty("ok",    false);
            result.addProperty("error", e.getMessage());
        }
        res.getWriter().write(GSON.toJson(result));
    }

    private static String nvl(String v, String def) { return (v != null && !v.isBlank()) ? v : def; }

    private static int parsePort(String v) {
        if (v == null || v.isBlank()) return AppConfig.getInt("mpd.port");
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 6600; }
    }
}