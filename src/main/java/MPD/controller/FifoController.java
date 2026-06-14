package MPD.controller;

import MPD.service.FifoService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/FifoServlet")
public class FifoController {

    private final FifoService fifoService;

    public FifoController(FifoService fifoService) { this.fifoService = fifoService; }

    /**
     * GET /FifoServlet  →  text/event-stream
     * Each event: "data:0,12,255,…"  at ~35 fps
     *
     * Each SSE client gets its own SseEmitter; the FifoService daemon thread
     * does all the I/O and FFT.  This method just fans out the latest bins.
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(-1L);

        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        emitter.onCompletion(() -> active.set(false));
        emitter.onTimeout(()    -> active.set(false));
        emitter.onError(t       -> active.set(false));

        Thread.ofVirtual().start(() -> {
            try {
                while (active.get()) {
                    byte[] bins = fifoService.latestBins();

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < bins.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(bins[i] & 0xFF);
                    }

                    if (!active.get()) break;
                    try {
                        emitter.send(SseEmitter.event().data(sb.toString()));
                    } catch (IOException e) {
                        // Broken pipe — client is gone. Don't call emitter.complete()
                        // (the connection is already dead; doing so just triggers another error).
                        active.set(false);
                        break;
                    } catch (IllegalStateException e) {
                        // Emitter already completed/errored (race with onError callback)
                        active.set(false);
                        break;
                    }
                    Thread.sleep(28); // ~35 fps
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        return emitter;
    }
}
