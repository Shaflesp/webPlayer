package MPD.controller;

import MPD.DependencyManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
