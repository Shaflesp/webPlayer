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
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/SyncServlet")
public class SyncController {

    private final SyncService syncService;
    private final MpdService  mpdService;

    public SyncController(SyncService syncService, MpdService mpdService) {
        this.syncService = syncService;
        this.mpdService  = mpdService;
    }

    @GetMapping
    public Object get(@RequestParam String action,
                      @RequestParam(required = false) String jobId) {
        return switch (action) {
            case "stream" -> stream(jobId);
            case "list"   -> syncService.listSyncedPlaylists();
            default       -> ResponseEntity.badRequest().body(Map.of("error", "Unknown action: " + action));
        };
    }

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
            default -> ResponseEntity.badRequest().body(Map.of("error", "Unknown action: " + action));
        };
    }

    private SseEmitter stream(String jobId) {
        SseEmitter emitter = new SseEmitter(-1L);

        if (jobId == null) { emitter.complete(); return emitter; }
        SyncJob job = syncService.getJob(jobId);
        if (job == null)  { emitter.complete(); return emitter; }

        AtomicBoolean active = new AtomicBoolean(true);
        emitter.onCompletion(() -> active.set(false));
        emitter.onTimeout(()    -> active.set(false));
        emitter.onError(t       -> active.set(false));

        // NOTE: every .data(...) call below explicitly passes MediaType.TEXT_PLAIN.
        // Without it, the globally-registered GsonHttpMessageConverter (added for
        // /MPDServlet's JSON responses) can intercept these plain-string sends and
        // JSON-encode them — wrapping each log line in literal quote characters.
        // Same root cause as the FifoController fix; affects any SSE controller
        // that sends plain strings.
        syncService.subscribe(
                job,
                line -> {
                    if (!active.get()) return;
                    try {
                        emitter.send(SseEmitter.event().data(line, MediaType.TEXT_PLAIN));
                    } catch (IOException | IllegalStateException e) {
                        // Same fix as FifoController: completeWithError, not a bare
                        // flag-flip, so Spring tears down the AsyncContext immediately
                        // rather than leaving it for Tomcat to rediscover later.
                        active.set(false);
                        try { emitter.completeWithError(e); } catch (Exception ignored) {}
                    }
                },
                () -> {
                    if (!active.get()) return;
                    try {
                        if (job.playlist != null)
                            emitter.send(SseEmitter.event().name("playlist").data(job.playlist, MediaType.TEXT_PLAIN));
                        emitter.send(SseEmitter.event().name("done").data(job.ok ? "ok" : "error", MediaType.TEXT_PLAIN));
                        emitter.complete();
                    } catch (IOException | IllegalStateException e) {
                        try { emitter.completeWithError(e); } catch (Exception ignored) {}
                    }
                }
        );

        return emitter;
    }
}