package MPD;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads MPD's FIFO output (/tmp/mpd.fifo by default), computes a real FFT
 * on every 2048-frame chunk (≈46 ms at 44100 Hz), and streams the resulting
 * 64 frequency bins to the browser as a Server-Sent Events endpoint.
 *
 * A single daemon thread owns the FIFO read loop so multiple browser tabs
 * don't starve each other.  Each SSE client gets the same latest frame.
 *
 * GET /FifoServlet  →  text/event-stream  "data:0,12,255,…\n\n"
 */
@WebServlet("/FifoServlet")
public class FifoServlet extends HttpServlet {

    // ── DSP constants ─────────────────────────────────────────────────────────
    private static final int FFT_SIZE    = 2048;          // must be power of 2
    private static final int FRAME_BYTES = 4;             // 16-bit stereo = 4 B/frame
    private static final int CHUNK_BYTES = FFT_SIZE * FRAME_BYTES;
    private static final int OUT_BINS    = 64;
    private static final int SAMPLE_RATE = 44100;

    // ── Shared state (reader thread → SSE threads) ────────────────────────────
    private static final AtomicReference<byte[]> LATEST_RAW =
            new AtomicReference<>(new byte[OUT_BINS]);   // 0-255 per bin
    private static final AtomicLong LAST_READ_MS = new AtomicLong(0);

    private static volatile Thread reader = null;

    // ── SSE endpoint ──────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws IOException {

        ensureReader();

        res.setContentType("text/event-stream;charset=UTF-8");
        res.setHeader("Cache-Control", "no-cache");
        res.setHeader("Connection",    "keep-alive");
        res.setCharacterEncoding("UTF-8");
        res.flushBuffer();

        PrintWriter out = res.getWriter();

        try {
            while (true) {
                boolean stale = (System.currentTimeMillis() - LAST_READ_MS.get()) > 120;
                byte[]  bins  = stale ? new byte[OUT_BINS] : LATEST_RAW.get();

                StringBuilder sb = new StringBuilder("data:");
                for (int i = 0; i < bins.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(bins[i] & 0xFF);
                }
                sb.append("\n\n");

                out.write(sb.toString());
                out.flush();
                if (out.checkError()) break;   // client gone

                Thread.sleep(28);              // ~35 fps ceiling
            }
        } catch (InterruptedException ignored) {
        }
    }

    // ── Reader thread lifecycle ───────────────────────────────────────────────

    private static synchronized void ensureReader() {
        if (reader != null && reader.isAlive()) return;
        reader = new Thread(FifoServlet::readLoop, "mpd-fifo-fft");
        reader.setDaemon(true);
        reader.start();
    }

    private static void readLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            String path = AppConfig.get("fifo.path");
            if (path == null || path.isBlank()) { sleep(2000); continue; }

            try (FileInputStream fis = new FileInputStream(path)) {
                byte[] buf = new byte[CHUNK_BYTES];
                while (!Thread.currentThread().isInterrupted()) {
                    // Read exactly one chunk (blocks until MPD produces data)
                    int pos = 0;
                    while (pos < CHUNK_BYTES) {
                        int n = fis.read(buf, pos, CHUNK_BYTES - pos);
                        if (n < 0) throw new EOFException("FIFO closed");
                        pos += n;
                    }
                    LATEST_RAW.set(process(buf));
                    LAST_READ_MS.set(System.currentTimeMillis());
                }
            } catch (IOException e) {
                // FIFO unavailable or MPD not configured — wait and retry
                LATEST_RAW.set(new byte[OUT_BINS]);
                sleep(500);
            }
        }
    }

    // ── DSP pipeline ─────────────────────────────────────────────────────────

    private static byte[] process(byte[] pcm) {
        // 1. PCM → mono doubles + Hanning window
        double[] re = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            int off = i * FRAME_BYTES;
            short L = (short)(((pcm[off+1] & 0xFF) << 8) | (pcm[off]   & 0xFF));
            short R = (short)(((pcm[off+3] & 0xFF) << 8) | (pcm[off+2] & 0xFF));
            double mono = (L + R) / 2.0 / 32768.0;
            double win  = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
            re[i] = mono * win;
        }

        // 2. In-place FFT
        double[] im = new double[FFT_SIZE];
        fft(re, im);

        // 3. Magnitude spectrum (skip DC, use first half)
        int half = FFT_SIZE / 2;
        double[] mag = new double[half];
        for (int i = 1; i < half; i++)
            mag[i] = Math.sqrt(re[i] * re[i] + im[i] * im[i]);

        // 4. Logarithmic frequency binning (20 Hz – 20 kHz)
        double logMin = Math.log10(20.0);
        double logMax = Math.log10(20000.0);
        double[] grouped = new double[OUT_BINS];

        for (int b = 0; b < OUT_BINS; b++) {
            double fLo = Math.pow(10, logMin + (double) b       / OUT_BINS * (logMax - logMin));
            double fHi = Math.pow(10, logMin + (double)(b + 1)  / OUT_BINS * (logMax - logMin));
            int iLo = Math.max(1,       (int)(fLo * FFT_SIZE / SAMPLE_RATE));
            int iHi = Math.min(half-1,  (int)(fHi * FFT_SIZE / SAMPLE_RATE) + 1);

            double sum = 0; int cnt = 0;
            for (int i = iLo; i <= iHi; i++) { sum += mag[i]; cnt++; }
            grouped[b] = cnt > 0 ? sum / cnt : 0;
        }

        // 5. Log normalise → 0..1, then pack to 0..255
        double peak = 0;
        for (double v : grouped) if (v > peak) peak = v;

        byte[] out = new byte[OUT_BINS];
        if (peak > 1e-9) {
            for (int b = 0; b < OUT_BINS; b++) {
                double norm = grouped[b] / peak;
                // log10(1 + 9x) maps 0..1 → 0..1 with log curve (matches ncmpcpp feel)
                double scaled = Math.log1p(norm * 9.0) / Math.log(10.0);
                out[b] = (byte) Math.min(255, Math.round(scaled * 255));
            }
        }
        return out;
    }

    /** In-place radix-2 Cooley-Tukey FFT. N must be a power of 2. */
    private static void fft(double[] re, double[] im) {
        int N = re.length;
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t; t=re[i]; re[i]=re[j]; re[j]=t;
                t=im[i]; im[i]=im[j]; im[j]=t;
            }
        }
        // Butterfly passes
        for (int len = 2; len <= N; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wRe = Math.cos(ang), wIm = Math.sin(ang);
            for (int i = 0; i < N; i += len) {
                double cRe = 1, cIm = 0;
                for (int j = 0, h = len >> 1; j < h; j++) {
                    int a = i+j, b = a+h;
                    double uRe=re[a], uIm=im[a];
                    double vRe=re[b]*cRe - im[b]*cIm;
                    double vIm=re[b]*cIm + im[b]*cRe;
                    re[a]=uRe+vRe; im[a]=uIm+vIm;
                    re[b]=uRe-vRe; im[b]=uIm-vIm;
                    double nRe=cRe*wRe - cIm*wIm;
                    cIm       =cRe*wIm + cIm*wRe;
                    cRe       =nRe;
                }
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}