package MPD.controller;

import MPD.DependencyManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Exposes system dependency health so the Settings panel can show
 * a "Dependencies" section (mpd / ffmpeg / yt-dlp) instead of failing
 * silently when one of them is missing.
 */
@RestController
@RequestMapping("/StatusServlet")
public class StatusController {

    private final DependencyManager deps;

    public StatusController(DependencyManager deps) { this.deps = deps; }

    @GetMapping
    public Map<String, Object> status() {
        return deps.statusMap();
    }

    /**
     * Triggers yt-dlp's own self-update mechanism. Synchronous and usually
     * takes a few seconds — the binary is small and the update check itself
     * is quick, so a simple blocking POST (no SSE/job-polling needed) is fine.
     */
    @PostMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        String action = (String) body.getOrDefault("action", "");
        if (!"updateYtDlp".equals(action)) {
            return Map.of("ok", false, "error", "Unknown action: " + action);
        }
        List<String> output = deps.updateYtDlp();
        return Map.of("ok", true, "output", output, "version", deps.getYtDlpVersion());
    }
}