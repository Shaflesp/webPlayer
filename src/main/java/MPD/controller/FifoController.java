package MPD.controller;

import MPD.service.FifoService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/FifoServlet")
public class FifoController {

    private final FifoService fifoService;

    public FifoController(FifoService fifoService) { this.fifoService = fifoService; }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(-1L);

        AtomicBoolean active = new AtomicBoolean(true);
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
                        emitter.send(SseEmitter.event().data(sb.toString(), MediaType.TEXT_PLAIN));
                    } catch (IOException | IllegalStateException e) {
                        active.set(false);
                        try { emitter.completeWithError(e); } catch (Exception ignored) {}
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