package MPD.controller;

import MPD.DependencyManager;
import MPD.service.AudioWatchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Exposes system dependency health (mpd/ffmpeg/yt-dlp) and handles the
 * browser-driven stream sync delay calibration.
 */
@RestController
@RequestMapping("/StatusServlet")
public class StatusController {

    private final DependencyManager deps;
    private final AudioWatchService audio;

    public StatusController(DependencyManager deps, AudioWatchService audio) {
        this.deps  = deps;
        this.audio = audio;
    }

    @GetMapping
    public Map<String, Object> status() {
        return deps.statusMap();
    }

    @PostMapping
    public Map<String, Object> post(@RequestBody Map<String, Object> body) {
        String action = (String) body.getOrDefault("action", "");
        return switch (action) {
            case "updateYtDlp" -> {
                List<String> output = deps.updateYtDlp();
                String version = deps.getYtDlpVersion();
                yield Map.of("ok", true, "output", output,
                             "version", version != null ? version : "unknown");
            }
            case "calibrateSyncDelay" -> {
                Object raw = body.get("delaySeconds");
                double delay = (raw instanceof Number n) ? n.doubleValue() : -1;
                if (delay < 0) yield Map.of("ok", false, "error", "delaySeconds is required and must be >= 0");
                try {
                    audio.applySyncDelay(delay);
                    yield Map.of("ok", true, "delaySeconds", delay);
                } catch (Exception e) {
                    yield Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : "Unknown error");
                }
            }
            default -> Map.of("ok", false, "error", "Unknown action: " + action);
        };
    }
}
