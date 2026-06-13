package MPD.controller;

import MPD.service.MpdService;
import MPD.service.SyncService;
import MPD.service.SyncService.SyncJob;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/SyncServlet")
public class SyncController {

    private final SyncService syncService;
    private final MpdService  mpdService;

    public SyncController(SyncService syncService, MpdService mpdService) {
        this.syncService = syncService;
        this.mpdService  = mpdService;
    }

    // ── GET ───────────────────────────────────────────────────────────────────

    @GetMapping
    public Object get(@RequestParam String action,
                      @RequestParam(required = false) String jobId) {
        return switch (action) {
            case "stream" -> stream(jobId);
            case "list"   -> syncService.listSyncedPlaylists();
            default       -> ResponseEntity.badRequest().body("Unknown action");
        };
    }

    /** SSE stream of yt-dlp output lines for a given jobId. */
    @GetMapping(value = "", params = "action=stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String jobId) {
        SyncJob job     = syncService.getJob(jobId);
        SseEmitter emitter = new SseEmitter(-1L);

        if (job == null) { emitter.complete(); return emitter; }

        syncService.subscribe(
            job,
            line -> {
                try {
                    emitter.send(SseEmitter.event().data(line));
                } catch (IOException e) {
                    emitter.complete();
                }
            },
            () -> {
                try {
                    if (job.playlist != null)
                        emitter.send(SseEmitter.event().name("playlist").data(job.playlist));
                    emitter.send(SseEmitter.event().name("done").data(job.ok ? "ok" : "error"));
                    emitter.complete();
                } catch (IOException ignored) { emitter.complete(); }
            }
        );

        return emitter;
    }

    // ── POST ──────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> post(@RequestBody Map<String, Object> body) {
        String action = (String) body.getOrDefault("action", "sync");
        return switch (action) {
            case "sync" -> {
                String url = (String) body.get("url");
                if (url == null || url.isBlank())
                    yield ResponseEntity.badRequest().body(Map.of("error", "url is required"));
                SyncJob job = syncService.startJob(url.trim());
                yield ResponseEntity.ok(Map.of("jobId", job.jobId));
            }
            case "updateMPD" -> {
                try {
                    mpdService.command("update");
                    yield ResponseEntity.ok(Map.of("ok", true));
                } catch (Exception e) {
                    yield ResponseEntity.ok(Map.of("ok", false, "error", e.getMessage()));
                }
            }
            default -> ResponseEntity.badRequest().body(Map.of("error", "unknown action"));
        };
    }

    /** Convenience typed list endpoint */
    @GetMapping(value = "", params = "action=list")
    public List<Map<String, Object>> list() {
        return syncService.listSyncedPlaylists();
    }
}
