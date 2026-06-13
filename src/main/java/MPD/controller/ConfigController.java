package MPD.controller;

import MPD.config.AppSettings;
import MPD.service.MpdService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ConfigServlet")
public class ConfigController {

    private final AppSettings settings;
    private final MpdService  mpdService;

    public ConfigController(AppSettings settings, MpdService mpdService) {
        this.settings   = settings;
        this.mpdService = mpdService;
    }

    /** GET /ConfigServlet  → all settings */
    @GetMapping
    public ResponseEntity<Map<String, String>> getAll(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String host,
            @RequestParam(required = false) String port) {

        if ("test".equals(action)) return ResponseEntity.ok(test(host, port));
        return ResponseEntity.ok(settings.getAll());
    }

    /** POST /ConfigServlet  body: { key: value } → { ok: true } */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, String> updates) {
        try {
            settings.saveAll(updates);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    private Map<String, String> test(String host, String port) {
        String h = (host != null && !host.isBlank()) ? host : settings.get("mpd.host");
        int    p;
        try { p = Integer.parseInt(port != null ? port.trim() : ""); }
        catch (NumberFormatException e) { p = settings.getInt("mpd.port"); }
        try {
            Map<String, String> st = mpdService.probe(h, p);
            return Map.of("ok", "true", "state", st.getOrDefault("state", "unknown"));
        } catch (Exception e) {
            return Map.of("ok", "false", "error", e.getMessage());
        }
    }
}
