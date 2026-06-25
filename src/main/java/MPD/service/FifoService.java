package MPD.service;

import MPD.config.AppSettings;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads MPD's FIFO PCM output, runs a Cooley-Tukey FFT on every 2048-frame
 * chunk, and exposes the latest 64 log-frequency bins so FifoController
 * can stream them to SSE clients.
 * <p>
 * The reader runs on a single virtual thread started at application startup
 * (@PostConstruct). Multiple SSE clients all read the same latest frame —
 * no per-client I/O.
 */
@Service
public class FifoService {

    // ── DSP constants ─────────────────────────────────────────────────────────
    static final int FFT_SIZE    = 2048;
    private static final int FRAME_BYTES = 4;       // 16-bit stereo
    private static final int CHUNK_BYTES = FFT_SIZE * FRAME_BYTES;
    static final int OUT_BINS    = 64;
    static final int SAMPLE_RATE = 44100;

    // ── Shared state ──────────────────────────────────────────────────────────
    private final AtomicReference<byte[]> latestBins =
            new AtomicReference<>(new byte[OUT_BINS]);
    private final AtomicLong lastReadMs = new AtomicLong(0);

    private final AppSettings settings;

    public FifoService(AppSettings settings) {
        this.settings = settings;
    }

    @PostConstruct
    public void start() {
        Thread.ofVirtual().name("fifo-fft").start(this::readLoop);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the latest FFT bins (0-255 per bin) or zeroes if FIFO is silent. */
    public byte[] latestBins() {
        boolean stale = System.currentTimeMillis() - lastReadMs.get() > 120;
        return stale ? new byte[OUT_BINS] : latestBins.get();
    }

    // ── Reader loop ───────────────────────────────────────────────────────────

    private void readLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            String path = settings.get("fifo.path");
            if (path == null || path.isBlank()) { sleep(2000); continue; }

            try (FileInputStream fis = new FileInputStream(path)) {
                byte[] buf = new byte[CHUNK_BYTES];
                while (!Thread.currentThread().isInterrupted()) {
                    int pos = 0;
                    while (pos < CHUNK_BYTES) {
                        int n = fis.read(buf, pos, CHUNK_BYTES - pos);
                        if (n < 0) throw new EOFException("FIFO closed");
                        pos += n;
                    }
                    latestBins.set(process(buf));
                    lastReadMs.set(System.currentTimeMillis());
                }
            } catch (IOException e) {
                latestBins.set(new byte[OUT_BINS]);
                sleep(500);
            }
        }
    }

    // ── DSP pipeline ─────────────────────────────────────────────────────────

    static byte[] process(byte[] pcm) {
        double[] re = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            int off = i * FRAME_BYTES;
            short L = (short)(((pcm[off+1] & 0xFF) << 8) | (pcm[off]   & 0xFF));
            short R = (short)(((pcm[off+3] & 0xFF) << 8) | (pcm[off+2] & 0xFF));
            double mono = (L + R) / 2.0 / 32768.0;
            double win  = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
            re[i] = mono * win;
        }

        double[] im = new double[FFT_SIZE];
        fft(re, im);

        int half = FFT_SIZE / 2;
        double[] mag = new double[half];
        for (int i = 1; i < half; i++)
            mag[i] = Math.sqrt(re[i] * re[i] + im[i] * im[i]);

        double logMin = Math.log10(20.0);
        double logMax = Math.log10(20000.0);
        double[] grouped = new double[OUT_BINS];

        for (int b = 0; b < OUT_BINS; b++) {
            double fLo = Math.pow(10, logMin + (double) b       / OUT_BINS * (logMax - logMin));
            double fHi = Math.pow(10, logMin + (double)(b + 1)  / OUT_BINS * (logMax - logMin));
            int iLo = Math.max(1,      (int)(fLo * FFT_SIZE / SAMPLE_RATE));
            int iHi = Math.min(half-1, (int)(fHi * FFT_SIZE / SAMPLE_RATE) + 1);
            double sum = 0; int cnt = 0;
            for (int i = iLo; i <= iHi; i++) { sum += mag[i]; cnt++; }
            grouped[b] = cnt > 0 ? sum / cnt : 0;
        }

        double peak = 0;
        for (double v : grouped) if (v > peak) peak = v;

        byte[] out = new byte[OUT_BINS];
        if (peak > 1e-9) {
            for (int b = 0; b < OUT_BINS; b++) {
                double scaled = Math.log1p(grouped[b] / peak * 9.0) / Math.log(10.0);
                out[b] = (byte) Math.min(255, Math.round(scaled * 255));
            }
        }
        return out;
    }

    static void fft(double[] re, double[] im) {
        int N = re.length;
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t; t=re[i]; re[i]=re[j]; re[j]=t;
                t=im[i]; im[i]=im[j]; im[j]=t;
            }
        }
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