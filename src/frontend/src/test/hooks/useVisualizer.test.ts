import { describe, it, expect } from 'vitest';
import {
  resample, mirrorSpectrum, boostTreble, applySensitivity, smoothTowards,
  TREBLE_BOOST, SENSITIVITY, ATTACK, RELEASE,
} from '../../hooks/useVisualizer';

// ── resample ──────────────────────────────────────────────────────────────────

describe('resample', () => {
  it('preserves the first and last values exactly', () => {
    const src = new Float32Array([0.1, 0.5, 0.9]);
    const out = resample(src, 5);
    expect(out[0]).toBeCloseTo(0.1);
    expect(out[4]).toBeCloseTo(0.9);
  });

  it('linearly interpolates a midpoint between two known values', () => {
    const src = new Float32Array([0, 1]); // exactly 2 points
    const out = resample(src, 3);         // upsample to 3: 0, 0.5, 1
    expect(out[0]).toBeCloseTo(0);
    expect(out[1]).toBeCloseTo(0.5);
    expect(out[2]).toBeCloseTo(1);
  });

  it('downsamples without throwing and keeps values in range', () => {
    const src = new Float32Array(64).map((_, i) => i / 64);
    const out = resample(src, 16);
    expect(out.length).toBe(16);
    for (const v of out) {
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThanOrEqual(1);
    }
  });

  it('handles a flat (constant) input correctly', () => {
    const src = new Float32Array(10).fill(0.42);
    const out = resample(src, 20);
    for (const v of out) expect(v).toBeCloseTo(0.42);
  });
});

// ── mirrorSpectrum ────────────────────────────────────────────────────────────

describe('mirrorSpectrum', () => {
  it('doubles the length', () => {
    const half = new Float32Array(10);
    expect(mirrorSpectrum(half).length).toBe(20);
  });

  it('the first and last elements are equal — this is what eliminates the ellipse seam', () => {
    const half = new Float32Array([0.9, 0.1, 0.1, 0.1]); // bass loud, treble quiet
    const out = mirrorSpectrum(half);
    // out[0] is bass (loud), out[N-1] should be the SAME bass value —
    // without this, bin[0] and bin[N-1] sitting next to each other at the
    // 12 o'clock wraparound would show a hard visual jump.
    expect(out[0]).toBeCloseTo(out[out.length - 1]);
    expect(out[0]).toBeCloseTo(0.9);
  });

  it('produces a true palindrome', () => {
    const half = new Float32Array([1, 2, 3, 4]);
    const out = mirrorSpectrum(half);
    for (let i = 0; i < out.length; i++) {
      expect(out[i]).toBeCloseTo(out[out.length - 1 - i]);
    }
  });

  it('preserves the original half values in the first half of the output', () => {
    const half = new Float32Array([0.1, 0.2, 0.3]);
    const out = mirrorSpectrum(half);
    expect(out[0]).toBeCloseTo(0.1);
    expect(out[1]).toBeCloseTo(0.2);
    expect(out[2]).toBeCloseTo(0.3);
  });
});

// ── boostTreble ───────────────────────────────────────────────────────────────

describe('boostTreble', () => {
  it('applies zero gain at the bass end (index 0)', () => {
    const spectrum = new Float32Array([0.5, 0.5, 0.5, 0.5]);
    const out = boostTreble(spectrum);
    expect(out[0]).toBeCloseTo(0.5); // gain = 1 + 0 * TREBLE_BOOST = 1x, unchanged
  });

  it(`applies exactly (1 + TREBLE_BOOST)x gain at the treble end, using the real current constant (${TREBLE_BOOST})`, () => {
    const spectrum = new Float32Array([0, 0, 0, 0.3]); // last bin = treble
    const out = boostTreble(spectrum);
    const expected = Math.min(1, 0.3 * (1 + TREBLE_BOOST));
    expect(out[3]).toBeCloseTo(expected);
  });

  it('never exceeds 1.0 even when boosted', () => {
    const spectrum = new Float32Array([0.9, 0.9, 0.9, 0.9]);
    const out = boostTreble(spectrum);
    for (const v of out) expect(v).toBeLessThanOrEqual(1);
  });

  it('gain increases monotonically from bass to treble', () => {
    // Same input value at every bin — any difference in output is purely
    // from the gain ramp, isolating the ramp's shape from the input data.
    const spectrum = new Float32Array(10).fill(0.5);
    const out = boostTreble(spectrum);
    for (let i = 1; i < out.length; i++) {
      expect(out[i]).toBeGreaterThanOrEqual(out[i - 1]);
    }
  });

  it('leaves a fully-silent spectrum silent', () => {
    const spectrum = new Float32Array(8); // all zeros
    const out = boostTreble(spectrum);
    for (const v of out) expect(v).toBe(0);
  });
});

// ── applySensitivity ──────────────────────────────────────────────────────────

describe('applySensitivity', () => {
  it(`divides every value by the real current SENSITIVITY constant (${SENSITIVITY})`, () => {
    const spectrum = new Float32Array([1, 0.5, 0.25]);
    const out = applySensitivity(spectrum);
    expect(out[0]).toBeCloseTo(1 / SENSITIVITY);
    expect(out[1]).toBeCloseTo(0.5 / SENSITIVITY);
    expect(out[2]).toBeCloseTo(0.25 / SENSITIVITY);
  });

  it('a bin sitting at the 1.0 ceiling never reaches full visual length — this is the whole point of the setting', () => {
    const spectrum = new Float32Array([1.0]);
    const out = applySensitivity(spectrum);
    expect(out[0]).toBeLessThan(1.0);
    expect(out[0]).toBeCloseTo(1 / SENSITIVITY);
  });

  it('preserves relative ordering between bins', () => {
    const spectrum = new Float32Array([0.2, 0.8, 0.5]);
    const out = applySensitivity(spectrum);
    expect(out[1]).toBeGreaterThan(out[2]);
    expect(out[2]).toBeGreaterThan(out[0]);
  });
});

// ── smoothTowards ─────────────────────────────────────────────────────────────

describe('smoothTowards', () => {
  it(`moves toward a rising target by exactly the ATTACK rate (${ATTACK})`, () => {
    const cur = new Float32Array([0]);
    const tgt = new Float32Array([1]);
    smoothTowards(cur, tgt);
    expect(cur[0]).toBeCloseTo(ATTACK); // 0 + (1-0)*ATTACK
  });

  it(`moves toward a falling target by exactly the RELEASE rate (${RELEASE})`, () => {
    const cur = new Float32Array([1]);
    const tgt = new Float32Array([0]);
    smoothTowards(cur, tgt);
    expect(cur[0]).toBeCloseTo(1 - RELEASE); // 1 + (0-1)*RELEASE
  });

  it('release is slower than attack — this is what gives the smooth trailing decay', () => {
    expect(RELEASE).toBeLessThan(ATTACK);
  });

  it('converges toward the target over repeated calls without overshooting', () => {
    const cur = new Float32Array([0]);
    const tgt = new Float32Array([1]);
    for (let i = 0; i < 50; i++) smoothTowards(cur, tgt);
    expect(cur[0]).toBeCloseTo(1, 2);
  });

  it('ignores NaN in the target and leaves that bin unchanged — the core fix for permanent bin corruption', () => {
    const cur = new Float32Array([0.5, 0.3]);
    const tgt = new Float32Array([NaN, 0.8]);
    smoothTowards(cur, tgt);
    expect(cur[0]).toBe(0.5); // untouched — NaN was rejected
    expect(cur[1]).toBeGreaterThan(0.3); // normal bin still updated
  });

  it('ignores Infinity in the target the same way as NaN', () => {
    const cur = new Float32Array([0.5]);
    const tgt = new Float32Array([Infinity]);
    smoothTowards(cur, tgt);
    expect(cur[0]).toBe(0.5);
  });

  it('a single bad value never spreads to other bins', () => {
    const cur = new Float32Array([0.2, 0.2, 0.2]);
    const tgt = new Float32Array([NaN, 0.9, NaN]);
    smoothTowards(cur, tgt);
    expect(Number.isFinite(cur[0])).toBe(true);
    expect(Number.isFinite(cur[2])).toBe(true);
    expect(cur[1]).toBeGreaterThan(0.2);
  });

  it('mutates the current buffer in place rather than returning a new one', () => {
    const cur = new Float32Array([0]);
    const tgt = new Float32Array([1]);
    const returned = smoothTowards(cur, tgt);
    expect(returned).toBeUndefined();
    expect(cur[0]).not.toBe(0); // cur itself was mutated
  });
});

// ── Integration: the actual real-data pipeline order used in the hook ────────

describe('real-data pipeline (resample → boostTreble → applySensitivity, mirrored for ellipse)', () => {
  it('produces a seamless, sensitivity-scaled, treble-boosted ellipse spectrum end to end', () => {
    const raw = new Float32Array(64).map((_, i) => (i < 20 ? 0.9 : 0.1)); // loud bass, quiet treble
    const half = applySensitivity(boostTreble(resample(raw, 80)));
    const full = mirrorSpectrum(half);

    expect(full.length).toBe(160);
    // Seamless wraparound still holds after the full real pipeline, not just
    // in isolation — this is what actually matters for the rendered ellipse.
    expect(full[0]).toBeCloseTo(full[full.length - 1]);
    // Everything stays within the sensitivity-scaled ceiling
    for (const v of full) expect(v).toBeLessThanOrEqual(1 / SENSITIVITY + 1e-6);
  });
});
