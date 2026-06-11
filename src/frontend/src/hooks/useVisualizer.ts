import { useEffect, useRef, type RefObject } from 'react';
import { useStore } from '../store';

// ── Helpers ───────────────────────────────────────────────────────────────────

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** Linear interpolation from src (any length) to N bins */
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

interface SimState { phase: number; decay: number; }

function simulate(sim: SimState, N: number, playing: boolean): Float32Array {
  sim.decay = playing
    ? Math.min(1, sim.decay + 0.06)
    : Math.max(0, sim.decay - 0.025);
  if (sim.decay === 0) return new Float32Array(N);
  sim.phase += 0.15;
  const beat = Math.max(0, Math.sin(sim.phase * 0.85)) ** 8;
  const d = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const t    = i / N;
    const bass = Math.exp(-t * 9)               * (0.40 + 0.55 * Math.sin(sim.phase * 1.7  + i * 0.28));
    const mid  = Math.exp(-((t - 0.22) ** 2) / 0.012) * (0.32 + 0.28 * Math.sin(sim.phase * 2.4  + i * 0.14));
    const high = Math.exp(-((t - 0.55) ** 2) / 0.030) * (0.14 + 0.14 * Math.cos(sim.phase * 3.3  + i * 0.45));
    const kick = Math.exp(-t * 12) * beat * 0.55;
    const noise = (Math.random() - 0.30) * 0.20;
    d[i] = Math.max(0, Math.min(1, (bass + mid + high + kick + noise) * sim.decay));
  }
  return d;
}

// ── Drawing ───────────────────────────────────────────────────────────────────

function drawEllipse(
  ctx: CanvasRenderingContext2D,
  W: number, H: number,
  cx: number, cy: number, vr: number,
  data: Float32Array,
  r: number, g: number, b: number,
) {
  ctx.clearRect(0, 0, W, H);
  const N = data.length;
  const rx = vr * 1.04, ry = vr * 0.99, maxBar = vr * 0.7;

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

function drawBar(
  ctx: CanvasRenderingContext2D,
  W: number, H: number,
  data: Float32Array,
  r: number, g: number, b: number,
) {
  ctx.clearRect(0, 0, W, H);
  const barW = W / data.length;
  for (let i = 0; i < data.length; i++) {
    const amp = data[i];
    if (amp < 0.01) continue;
    ctx.fillStyle = `rgba(${r},${g},${b},${0.12 + amp * 0.32})`;
    ctx.fillRect(i * barW, H - amp * H * 0.68, Math.max(1, barW - 0.8), amp * H * 0.68);
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

  /**
   * FFT bins from the FIFO SSE endpoint.
   * Stored in a ref — NOT React state — so the 35 fps SSE stream never
   * triggers a React re-render.  The canvas rAF loop reads it directly.
   */
  const binsRef = useRef<Float32Array | null>(null);
  const simRef  = useRef<SimState>({ phase: 0, decay: 0 });

  // ── FIFO EventSource ──────────────────────────────────────────────────────
  useEffect(() => {
    const es = new EventSource('/FifoServlet');
    es.onmessage = (e: MessageEvent<string>) => {
      const vals = e.data.split(',');
      const bins = new Float32Array(vals.length);
      for (let i = 0; i < vals.length; i++) bins[i] = parseInt(vals[i]) / 255;
      binsRef.current = bins;
    };
    es.onerror = () => { binsRef.current = null; };
    return () => es.close();
  }, []); // single connection for the lifetime of the page

  // ── rAF drawing loop ──────────────────────────────────────────────────────
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d')!;
    let animId: number;
    const [r, g, b] = hexToRgb(accent);

    const frame = () => {
      // Keep canvas size in sync with its section (now-playing fills remaining height)
      const parent = canvas.parentElement;
      if (parent) {
        if (canvas.width  !== parent.clientWidth)  canvas.width  = parent.clientWidth;
        if (canvas.height !== parent.clientHeight) canvas.height = parent.clientHeight;
      }
      const W = canvas.width, H = canvas.height;

      if (mode === 'off') {
        ctx.clearRect(0, 0, W, H);
        animId = requestAnimationFrame(frame);
        return;
      }

      const N    = mode === 'ellipse' ? 220 : 90;
      const raw  = binsRef.current;
      const data = raw ? resample(raw, N) : simulate(simRef.current, N, state === 'play');

      if (mode === 'ellipse' && vinylRef.current && canvas.parentElement) {
        const vr = vinylRef.current.getBoundingClientRect();
        const nr = canvas.parentElement.getBoundingClientRect();
        drawEllipse(
          ctx, W, H,
          vr.left + vr.width  / 2 - nr.left,
          vr.top  + vr.height / 2 - nr.top,
          vr.width / 2,
          data, r, g, b,
        );
      } else if (mode === 'bar') {
        drawBar(ctx, W, H, data, r, g, b);
      } else {
        ctx.clearRect(0, 0, W, H);
      }

      animId = requestAnimationFrame(frame);
    };

    animId = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(animId);
    // Recreate only when visual settings change, not on every status poll
  }, [mode, accent, state]);
}
