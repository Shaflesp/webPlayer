import { useEffect } from 'react';
import { useStore } from './store';
import { usePoller } from './hooks/usePoller';
import { Sidebar }    from './components/Sidebar';
import { NowPlaying } from './components/NowPlaying';
import { PlayerBar }  from './components/PlayerBar';
import { Settings }   from './components/Settings';
import { SyncPanel }  from './components/SyncPanel';
import {
  play, pause, resume, next, previous, setVol,
  fetchSettings, fetchDependencyStatus,
} from './api';
import type { DependencyStatus } from './types';

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const n = parseInt(h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** Builds one combined message from whichever dependencies are missing, or null if all are fine. */
function buildDepWarning(status: DependencyStatus): string | null {
  const issues: string[] = [];
  if (!status.mpd.ok)    issues.push('MPD not detected');
  if (!status.ffmpeg.ok) issues.push('ffmpeg not detected');
  if (!status.ytdlp.ok)  issues.push('yt-dlp not available');
  if (issues.length === 0) return null;
  return issues.join(' · ') + ' — some features may not work.';
}

export function App() {
  const status      = useStore(s => s.status);
  const settings    = useStore(s => s.settings);
  const setSettings = useStore(s => s.setSettings);
  const errorMsg     = useStore(s => s.errorMsg);
  const setError     = useStore(s => s.setError);
  const depWarning    = useStore(s => s.depWarning);
  const setDepWarning = useStore(s => s.setDepWarning);

  // ── Hydrate persisted settings from the server on startup ─────────────────
  useEffect(() => {
    fetchSettings()
        .then(setSettings)
        .catch(() => { /* server not reachable yet — keep defaults, poller will retry */ });
  }, []);

  // ── Check dependency health once on startup ────────────────────────────────

  useEffect(() => {
    fetchDependencyStatus()
        .then(s => setDepWarning(buildDepWarning(s)))
        .catch(() => { /* status endpoint not reachable — not critical, skip */ });
  }, []);

  usePoller();

  // ── Sync CSS custom properties when settings change ───────────────────────
  useEffect(() => {
    const hex = settings['ui.accentColor'] ?? '#7c3aed';
    const [r, g, b] = hexToRgb(hex);
    const root = document.documentElement;
    root.style.setProperty('--accent',      hex);
    root.style.setProperty('--accent-dim',  `rgb(${Math.max(0,r-30)},${Math.max(0,g-30)},${Math.max(0,b-30)})`);
    root.style.setProperty('--accent-glow', `rgba(${r},${g},${b},.35)`);
  }, [settings['ui.accentColor']]);

  useEffect(() => {
    document.documentElement.style.setProperty(
        '--vinyl-speed', `${settings['ui.vinylSpeed'] ?? '6'}s`,
    );
  }, [settings['ui.vinylSpeed']]);

  useEffect(() => {
    document.documentElement.style.setProperty(
        '--bg-opacity', settings['ui.bgOpacity'] ?? '0.20',
    );
  }, [settings['ui.bgOpacity']]);

  // ── Keyboard shortcuts ────────────────────────────────────────────────────
  useEffect(() => {
    const onKey = async (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).tagName === 'INPUT') return;
      const vol = status.volume;
      switch (e.code) {
        case 'Space':
          e.preventDefault();
          if      (status.state === 'play')  await pause();
          else if (status.state === 'pause') await resume();
          else                               await play();
          break;
        case 'ArrowRight': e.preventDefault(); await next();     break;
        case 'ArrowLeft':  e.preventDefault(); await previous(); break;
        case 'ArrowUp':    e.preventDefault(); await setVol(Math.min(100, vol + 5)); break;
        case 'ArrowDown':  e.preventDefault(); await setVol(Math.max(0,   vol - 5)); break;
        case 'KeyS': await import('./api').then(a => a.toggleRandom()); break;
        case 'KeyR': await import('./api').then(a => a.toggleRepeat()); break;
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [status.state, status.volume]);

  return (
      <div className="app">
        <Sidebar />

        <div className="main-col">
          {errorMsg && (
              <div className="error-banner">
                <i className="fas fa-triangle-exclamation" />
                <span>{errorMsg}</span>
                <button onClick={() => setError(null)}>
                  <i className="fas fa-xmark" />
                </button>
              </div>
          )}

          {depWarning && (
              <div className="error-banner" style={{ top: errorMsg ? 56 : 12 }}>
                <i className="fas fa-triangle-exclamation" />
                <span>{depWarning}</span>
                <button onClick={() => setDepWarning(null)}>
                  <i className="fas fa-xmark" />
                </button>
              </div>
          )}

          <NowPlaying />
          <PlayerBar />
        </div>

        <Settings />
        <SyncPanel />
      </div>
  );
}