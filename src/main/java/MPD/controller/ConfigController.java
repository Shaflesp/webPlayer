package MPD.controller;

import MPD.config.AppSettings;
import MPD.service.MpdService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/ConfigServlet")
public class ConfigController {

    private final AppSettings settings;
    private final MpdService  mpdService;

    public ConfigController(AppSettings settings, MpdService mpdService) {
        this.settings   = settings;
        this.mpdService = mpdService;
    }

    /** 
     * GET /ConfigServlet              → all settings as { key: value }
     * GET /ConfigServlet?action=test  → { ok: boolean, state?, error? }
     */
    @GetMapping
    public Object get(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String host,
            @RequestParam(required = false) String port) {

        if ("test".equals(action)) return test(host, port);
        return settings.getAll();
    }

    /** POST /ConfigServlet  body: { key: value, … } → { ok: boolean } */
    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, String> updates) {
        try {
            settings.saveAll(updates);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> test(String host, String port) {
        String h = (host != null && !host.isBlank()) ? host : settings.get("mpd.host");
        int p;
        try { p = Integer.parseInt(port != null ? port.trim() : ""); }
        catch (NumberFormatException e) { p = settings.getInt("mpd.port"); }

        try {
            Map<String, String> st = mpdService.probe(h, p);
            // Return real booleans — not strings — so the frontend's `if (res.ok)` works
            return Map.of("ok", true, "state", st.getOrDefault("state", "unknown"));
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
