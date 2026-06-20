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
                        // IMPORTANT: explicit MediaType.TEXT_PLAIN.
                        // Without this, Spring's converter resolution can pick the
                        // globally-registered GsonHttpMessageConverter (added for
                        // /MPDServlet's JSON responses) instead of a plain string
                        // converter — and Gson serializes a String as a JSON string
                        // literal, wrapping it in quotes. The browser then receives
                        // "12,45,200,..." (quotes included as literal text), which
                        // breaks parseInt on the first/last value of every frame.
                        emitter.send(SseEmitter.event().data(sb.toString(), MediaType.TEXT_PLAIN));
                    } catch (IOException | IllegalStateException e) {
                        // Client disconnected. completeWithError() (not a bare break,
                        // and not complete()) is what actually matters here: it tells
                        // Spring's async machinery the stream died due to an error so
                        // it tears down the AsyncContext immediately. Leaving it
                        // dangling is what causes Tomcat to rediscover the dead socket
                        // later on ITS OWN thread (tomcat-handler-N, not this one) and
                        // retry a flush there — outside this try/catch entirely, which
                        // is exactly the unhandled "Relais brisé (pipe)" seen in logs.
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