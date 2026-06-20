import { useEffect, useRef, type RefObject } from 'react';
import { useStore } from '../store';

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function resample(src: Float32Array, N: number): Float32Array {
  const out = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const pos = (i / (N - 1)) * (src.length - 1);
    const lo  = Math.floor(pos);
    const hi  = Math.min(lo + 1, src.length - 1);
    out[i]    = src[lo] * (1 - (pos - lo)) + src[hi] * (pos - lo);
  }
  return out;
}

/**
 * Mirror the spectrum into a palindrome: bass→treble→bass around the ring.
 * Without this, bin[0] (bass, loud) sits directly next to bin[N-1] (treble,
 * quiet) at the 12-o'clock seam, creating a hard jump. Mirroring makes both
 * ends of the array meet at the same value so the ring is seamless.
 */
function mirrorSpectrum(half: Float32Array): Float32Array {
  const N   = half.length * 2;
  const out = new Float32Array(N);
  for (let i = 0; i < half.length; i++) {
    out[i]             = half[i];
    out[N - 1 - i]    = half[i];
  }
  return out;
}

/**
 * Most music carries dramatically more raw energy in bass than treble.
 * Mapping that directly to radius/height makes the visualization look
 * lopsided — a big bulge where bass sits, almost flat where treble sits —
 * even though the underlying geometry (mirroring, resampling) is perfectly
 * even. This applies a gentle per-bin gain ramp, no boost at the bass end,
 * increasing toward the treble end, purely to make the shape read as
 * balanced and lively. It has nothing to do with acoustic accuracy.
 */
const TREBLE_BOOST = 1.3;   // treble bins get up to (1 + TREBLE_BOOST)x gain
function boostTreble(spectrum: Float32Array): Float32Array {
  const M = spectrum.length;
  const out = new Float32Array(M);
  for (let i = 0; i < M; i++) {
    const gain = 1 + (i / (M - 1)) * TREBLE_BOOST;
    out[i] = Math.min(1, spectrum[i] * gain);
  }
  return out;
}

/**
 * Each FFT frame is normalized so its OWN loudest bin always sits near 1.0,
 * regardless of how loud the song actually is overall. For genres with energy
 * spread across the spectrum at once (rock, EDM — drums+bass+guitar+cymbals
 * all loud simultaneously), this means MANY bins end up near that same
 * ceiling together, so all the spike tips land at nearly the same radius and
 * the shape collapses into a solid ring instead of a varied curve.
 *
 * Dividing by SENSITIVITY before mapping to visual length adds headroom: even
 * a bin sitting right at the 1.0 ceiling only reaches (1 / SENSITIVITY) of
 * its maximum length, so variation between bins stays visible instead of
 * everything piling up against the edge.
 *
 * Lower value (e.g. 1.0)  → more sensitive, maxes out easily, "fuller" look.
 * Higher value (e.g. 2.5) → less sensitive, more headroom, more spiky/varied.
 */
const SENSITIVITY = 2.5;
function applySensitivity(spectrum: Float32Array): Float32Array {
  const out = new Float32Array(spectrum.length);
  for (let i = 0; i < spectrum.length; i++) out[i] = spectrum[i] / SENSITIVITY;
  return out;
}

// Asymmetric envelope: fast attack (responsive), slow release (smooth decay)
const ATTACK = 0.40, RELEASE = 0.07;
function smoothTowards(cur: Float32Array, tgt: Float32Array) {
  for (let i = 0; i < cur.length; i++) {
    const t = tgt[i];
    // Defensive: never let a bad value corrupt the persistent buffer. Without
    // this, a single NaN entering once (e.g. a malformed SSE frame) would
    // poison that bin PERMANENTLY — NaN + anything is still NaN on every
    // future frame, with no way to recover until reload.
    if (!Number.isFinite(t)) continue;
    const rate = t > cur[i] ? ATTACK : RELEASE;
    cur[i] += (t - cur[i]) * rate;
  }
}

interface SimState { phase: number; decay: number; }
function simulate(sim: SimState, N: number, playing: boolean): Float32Array {
  sim.decay = playing ? Math.min(1, sim.decay + 0.06) : Math.max(0, sim.decay - 0.025);
  if (sim.decay === 0) return new Float32Array(N);
  sim.phase += 0.15;
  const beat = Math.max(0, Math.sin(sim.phase * 0.85)) ** 8;
  const d = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const t = i / N;
    d[i] = Math.max(0, Math.min(1, (
        Math.exp(-t * 9)                      * (0.40 + 0.55 * Math.sin(sim.phase * 1.7  + i * 0.28)) +
        Math.exp(-((t-0.22)**2) / 0.012)     * (0.32 + 0.28 * Math.sin(sim.phase * 2.4  + i * 0.14)) +
        Math.exp(-((t-0.55)**2) / 0.030)     * (0.14 + 0.14 * Math.cos(sim.phase * 3.3  + i * 0.45)) +
        Math.exp(-t * 12) * beat * 0.55 +
        (Math.random() - 0.30) * 0.20
    ) * sim.decay));
  }
  return d;
}

// ── Ellipse ───────────────────────────────────────────────────────────────────

// ── Ellipse — discrete radial spikes (preferred over the smooth blob) ─────────

function drawEllipse(
    ctx: CanvasRenderingContext2D, W: number, H: number,
    cx: number, cy: number, vr: number,
    data: Float32Array, r: number, g: number, b: number,
) {
  ctx.clearRect(0, 0, W, H);
  const N = data.length;
  const rx = vr * 1.04, ry = vr * 0.99, maxBar = vr * 0.7;

  // Reference ring
  ctx.beginPath();
  ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
  ctx.strokeStyle = `rgba(${r},${g},${b},0.13)`;
  ctx.lineWidth = 1;
  ctx.stroke();

  for (let i = 0; i < N; i++) {
    const amp = data[i];
    if (amp < 0.01) continue;
    const angle  = (i / N) * Math.PI * 2 - Math.PI / 2;
    const barLen = amp * maxBar;
    const bx = cx + rx * Math.cos(angle);
    const by = cy + ry * Math.sin(angle);
    ctx.shadowColor = `rgba(${r},${g},${b},0.55)`;
    ctx.shadowBlur  = 4 + amp * 10;
    ctx.beginPath();
    ctx.moveTo(bx, by);
    ctx.lineTo(bx + Math.cos(angle) * barLen, by + Math.sin(angle) * barLen);
    ctx.strokeStyle = `rgba(${r},${g},${b},${0.25 + amp * 0.75})`;
    ctx.lineWidth   = 1.2 + amp * 2.2;
    ctx.stroke();
  }
  ctx.shadowBlur = 0;
}

// ── Bar — compact strip near the bottom, not a tall expanse ───────────────────

function drawBar(
    ctx: CanvasRenderingContext2D, W: number, H: number,
    data: Float32Array, r: number, g: number, b: number,
) {
  ctx.clearRect(0, 0, W, H);
  const N = data.length, gap = 2, barW = W / N;
  const innerW = Math.max(1, barW - gap);
  const baseline = H * 0.85;   // was 0.75 — sits lower, out of the way
  const maxH     = H * 0.28;   // was 0.65 — much shorter bars

  for (let i = 0; i < N; i++) {
    const amp = data[i];
    if (amp < 0.01) continue;
    const h = amp * maxH, x = i * barW + gap / 2, top = baseline - h;
    const radius = Math.min(innerW / 2, 5);

    const grad = ctx.createLinearGradient(0, top, 0, baseline);
    grad.addColorStop(0, `rgba(${r},${g},${b},0.95)`);
    grad.addColorStop(1, `rgba(${r},${g},${b},0.35)`);
    ctx.fillStyle = grad;

    ctx.beginPath();
    ctx.moveTo(x, baseline);
    ctx.lineTo(x, top + radius);
    ctx.arcTo(x, top, x + radius, top, radius);
    ctx.lineTo(x + innerW - radius, top);
    ctx.arcTo(x + innerW, top, x + innerW, top + radius, radius);
    ctx.lineTo(x + innerW, baseline);
    ctx.closePath();
    ctx.fill();

    if (amp > 0.5) {
      ctx.shadowColor = `rgba(${r},${g},${b},${(amp - 0.5) * 0.7})`;
      ctx.shadowBlur  = (amp - 0.5) * 20;
      ctx.fill();
      ctx.shadowBlur = 0;
    }

    // Reflection
    const rGrad = ctx.createLinearGradient(0, baseline, 0, baseline + h * 0.28);
    rGrad.addColorStop(0, `rgba(${r},${g},${b},0.18)`);
    rGrad.addColorStop(1, `rgba(${r},${g},${b},0)`);
    ctx.fillStyle = rGrad;
    ctx.fillRect(x, baseline + 1, innerW, h * 0.28);
  }
}

// ── Hook ──────────────────────────────────────────────────────────────────────

export function useVisualizer(
    canvasRef: RefObject<HTMLCanvasElement>,
    vinylRef:  RefObject<HTMLDivElement>,
) {
  const mode   = useStore(s => s.settings['visualizer.mode'] ?? 'ellipse');
  const accent = useStore(s => s.settings['ui.accentColor']  ?? '#7c3aed');
  const state  = useStore(s => s.status.state);

  const targetRef   = useRef<Float32Array | null>(null);
  const smoothedRef = useRef<Float32Array>(new Float32Array(64));
  const simRef      = useRef<SimState>({ phase: 0, decay: 0 });

  useEffect(() => {
    const es = new EventSource('/FifoServlet');
    es.onmessage = (e: MessageEvent<string>) => {
      const vals = e.data.split(',');
      const bins = new Float32Array(vals.length);
      // Substitute silence (0) for any single bad value rather than discarding
      // the whole frame — a frame that's mostly valid should still be used.
      // (Discarding the entire frame on any single bad value is what turned a
      // minor encoding glitch into a permanent fallback to simulate() — see
      // the FifoController fix for the actual root cause.)
      for (let i = 0; i < vals.length; i++) {
        const v = parseInt(vals[i], 10);
        bins[i] = Number.isFinite(v) ? v / 255 : 0;
      }
      targetRef.current = bins;
    };
    es.onerror = () => { targetRef.current = null; };
    return () => es.close();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d')!;
    let animId: number;
    const [r, g, b] = hexToRgb(accent);

    const frame = () => {
      const parent = canvas.parentElement;
      if (parent) {
        if (canvas.width  !== parent.clientWidth)  canvas.width  = parent.clientWidth;
        if (canvas.height !== parent.clientHeight) canvas.height = parent.clientHeight;
      }

      if (mode === 'off') { ctx.clearRect(0, 0, canvas.width, canvas.height); animId = requestAnimationFrame(frame); return; }

      const HALF = mode === 'ellipse' ? 80 : 90;
      const raw  = targetRef.current;

      let data: Float32Array;
      if (raw) {
        smoothTowards(smoothedRef.current, raw);
        data = mode === 'ellipse'
            ? mirrorSpectrum(applySensitivity(boostTreble(resample(smoothedRef.current, HALF))))
            : applySensitivity(boostTreble(resample(smoothedRef.current, HALF)));
      } else {
        const N = mode === 'ellipse' ? HALF * 2 : HALF;
        data = simulate(simRef.current, N, state === 'play');
      }

      if (mode === 'ellipse' && vinylRef.current && canvas.parentElement) {
        const vr = vinylRef.current.getBoundingClientRect();
        const nr = canvas.parentElement.getBoundingClientRect();
        drawEllipse(ctx, canvas.width, canvas.height,
            vr.left + vr.width  / 2 - nr.left,
            vr.top  + vr.height / 2 - nr.top,
            vr.width / 2, data, r, g, b);
      } else if (mode === 'bar') {
        drawBar(ctx, canvas.width, canvas.height, data, r, g, b);
      } else {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
      }

      animId = requestAnimationFrame(frame);
    };

    animId = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(animId);
  }, [mode, accent, state]);
}