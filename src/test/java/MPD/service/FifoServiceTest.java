package MPD.service;

import org.junit.jupiter.api.*;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the DSP pipeline with synthetic signals — no real FIFO, no real
 * audio, no MPD needed. fft() and process() are package-private specifically
 * to make this possible (see FifoService for the visibility note).
 */
class FifoServiceTest {

    // ── fft() — raw transform correctness, small N for focused testing ───────

    @Test
    void fft_pureCosineProducesPeakAtTargetBin() {
        int n = 64;
        int targetBin = 5;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.cos(2 * Math.PI * targetBin * i / n);

        FifoService.fft(re, im);
        double[] mag = magnitudes(re, im);

        assertEquals(targetBin, indexOfMax(mag, 0, n / 2),
            "peak should land exactly at the bin matching the input frequency");
    }

    @Test
    void fft_realInputProducesConjugateMirrorPeak() {
        // A real-valued signal's spectrum is symmetric: energy at bin k
        // also appears at bin N-k. This is what lets process() safely only
        // look at the first half of the spectrum (it does).
        int n = 64;
        int targetBin = 5;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.cos(2 * Math.PI * targetBin * i / n);

        FifoService.fft(re, im);
        double[] mag = magnitudes(re, im);

        assertTrue(mag[n - targetBin] > mag[n - targetBin - 2],
            "expected a mirrored peak near bin N-targetBin too");
    }

    @Test
    void fft_constantSignalProducesOnlyDcComponent() {
        int n = 64;
        double[] re = new double[n];
        double[] im = new double[n];
        Arrays.fill(re, 1.0); // pure DC, zero oscillation

        FifoService.fft(re, im);
        double[] mag = magnitudes(re, im);

        assertEquals(n, mag[0], 1e-9, "DC bin should equal the sum of all samples (N * 1.0)");
        for (int i = 1; i < n; i++) {
            assertTrue(mag[i] < 1e-9, "bin " + i + " should be ~zero for pure DC input, was " + mag[i]);
        }
    }

    @Test
    void fft_zeroInputProducesZeroOutput() {
        int n = 64;
        double[] re = new double[n];
        double[] im = new double[n];

        FifoService.fft(re, im);

        for (double v : re) assertEquals(0.0, v, 1e-12);
        for (double v : im) assertEquals(0.0, v, 1e-12);
    }

    // ── process() — full pipeline with synthetic PCM ──────────────────────────

    @Test
    void process_pureToneProducesPeakInExpectedLogFrequencyBin() {
        // rawBinIndex=100 → exactly 100 full cycles across the 2048-sample
        // window at 44100Hz, so even before windowing this tone aligns
        // perfectly onto a single raw FFT bin (minimal spectral leakage).
        int rawBinIndex = 100;
        double freqHz = rawBinIndex * (double) FifoService.SAMPLE_RATE / FifoService.FFT_SIZE; // ≈2152.34 Hz

        byte[] pcm = generateStereoPCM(freqHz, FifoService.FFT_SIZE, FifoService.SAMPLE_RATE, 0.8);
        byte[] outBins = FifoService.process(pcm);

        int expectedBin = expectedLogBin(freqHz);
        int actualPeakBin = indexOfMaxUnsigned(outBins);

        assertEquals(expectedBin, actualPeakBin,
            "expected the peak output bin to match the log-frequency bin containing " + freqHz + " Hz");
    }

    @Test
    void process_lowAndHighFrequencyTonesProduceDifferentPeakBins() {
        // Sanity check that the pipeline is actually frequency-sensitive,
        // not just returning some static pattern regardless of input.
        double lowFreq  = 5   * (double) FifoService.SAMPLE_RATE / FifoService.FFT_SIZE;  // ≈108 Hz
        double highFreq = 800 * (double) FifoService.SAMPLE_RATE / FifoService.FFT_SIZE;  // ≈17226 Hz

        byte[] lowOut  = FifoService.process(generateStereoPCM(lowFreq,  FifoService.FFT_SIZE, FifoService.SAMPLE_RATE, 0.8));
        byte[] highOut = FifoService.process(generateStereoPCM(highFreq, FifoService.FFT_SIZE, FifoService.SAMPLE_RATE, 0.8));

        int lowPeak  = indexOfMaxUnsigned(lowOut);
        int highPeak = indexOfMaxUnsigned(highOut);

        assertNotEquals(lowPeak, highPeak);
        assertTrue(lowPeak < highPeak, "a lower frequency tone should land in a lower output bin");
    }

    @Test
    void process_silenceProducesAllZeroBins() {
        byte[] silentPcm = new byte[FifoService.FFT_SIZE * 4]; // all-zero bytes = silence
        byte[] outBins = FifoService.process(silentPcm);

        for (byte b : outBins) assertEquals(0, b);
    }

    @Test
    void process_normalizesPeakBinNearMaxRegardlessOfInputAmplitude() {
        // Per-frame peak-relative normalization means a quiet tone and a loud
        // tone at the SAME frequency should both top out near 255 in their
        // peak bin — this is also exactly why a quiet song and a loud song
        // can look similarly "full" in the visualizer (see the SENSITIVITY
        // discussion from earlier).
        double freq = 1000;
        byte[] loudOut  = FifoService.process(generateStereoPCM(freq, FifoService.FFT_SIZE, FifoService.SAMPLE_RATE, 0.9));
        byte[] quietOut = FifoService.process(generateStereoPCM(freq, FifoService.FFT_SIZE, FifoService.SAMPLE_RATE, 0.05));

        int loudPeak  = indexOfMaxUnsigned(loudOut);
        int quietPeak = indexOfMaxUnsigned(quietOut);

        assertTrue((loudOut[loudPeak] & 0xFF)   > 240, "loud tone's peak bin should be near max brightness");
        assertTrue((quietOut[quietPeak] & 0xFF) > 240, "quiet tone's peak bin should ALSO be near max — that's the point of per-frame normalization");
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    private static double[] magnitudes(double[] re, double[] im) {
        double[] mag = new double[re.length];
        for (int i = 0; i < re.length; i++) mag[i] = Math.hypot(re[i], im[i]);
        return mag;
    }

    private static int indexOfMax(double[] arr, int fromInclusive, int toExclusive) {
        int best = fromInclusive;
        for (int i = fromInclusive + 1; i < toExclusive; i++) if (arr[i] > arr[best]) best = i;
        return best;
    }

    /** Byte values must be read unsigned (0-255) — a raw signed byte comparison would be wrong near the ceiling. */
    private static int indexOfMaxUnsigned(byte[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) if ((arr[i] & 0xFF) > (arr[best] & 0xFF)) best = i;
        return best;
    }

    /** Mirrors FifoService.process()'s log-frequency bin boundaries to compute which output bin a frequency should land in. */
    private static int expectedLogBin(double freqHz) {
        double logMin = Math.log10(20.0), logMax = Math.log10(20000.0);
        int bin = (int) Math.floor(FifoService.OUT_BINS * (Math.log10(freqHz) - logMin) / (logMax - logMin));
        return Math.max(0, Math.min(FifoService.OUT_BINS - 1, bin));
    }

    /** Encodes a mono sine tone into interleaved 16-bit little-endian stereo PCM, matching process()'s decode logic exactly. */
    private static byte[] generateStereoPCM(double freqHz, int numFrames, int sampleRate, double amplitude) {
        byte[] pcm = new byte[numFrames * 4]; // 4 bytes/frame: L_low, L_high, R_low, R_high
        for (int i = 0; i < numFrames; i++) {
            double t = i / (double) sampleRate;
            short sample = (short) Math.round(amplitude * 32767 * Math.sin(2 * Math.PI * freqHz * t));
            int off = i * 4;
            pcm[off]     = (byte) (sample & 0xFF);
            pcm[off + 1] = (byte) ((sample >> 8) & 0xFF);
            pcm[off + 2] = (byte) (sample & 0xFF);
            pcm[off + 3] = (byte) ((sample >> 8) & 0xFF);
        }
        return pcm;
    }
}
