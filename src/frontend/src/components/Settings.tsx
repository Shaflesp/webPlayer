import { useState, useEffect, useCallback } from 'react';
import { useStore } from '../store';
import { fetchSettings, saveSettings, testConnection } from '../api';
import type { VizMode } from '../types';

// ── Helpers ───────────────────────────────────────────────────────────────────

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const n = parseInt(h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function applyAccent(hex: string) {
  const root = document.documentElement;
  const [r, g, b] = hexToRgb(hex);
  root.style.setProperty('--accent',      hex);
  root.style.setProperty('--accent-dim',  `rgb(${Math.max(0,r-30)},${Math.max(0,g-30)},${Math.max(0,b-30)})`);
  root.style.setProperty('--accent-glow', `rgba(${r},${g},${b},.35)`);
}

// ── Toggle switch ─────────────────────────────────────────────────────────────

function Toggle({ on, onClick }: { on: boolean; onClick: () => void }) {
  return (
    <button className={`toggle-switch${on ? ' on' : ''}`} onClick={onClick} type="button" />
  );
}

// ── Settings panel ────────────────────────────────────────────────────────────

export function Settings() {
  const open    = useStore(s => s.settingsOpen);
  const setOpen = useStore(s => s.setSettingsOpen);
  const setSettings    = useStore(s => s.setSettings);
  const patchSettings  = useStore(s => s.patchSettings);

  // Local form state — only pushed to Zustand + backend on save/change
  const [host,      setHost]      = useState('');
  const [port,      setPort]      = useState('');
  const [musicDir,  setMusicDir]  = useState('');
  const [connStatus, setConnStatus] = useState<{ ok: boolean; msg: string } | null>(null);

  const [pauseOnClose, setPauseOnClose] = useState(false);

  const [accent,    setAccent]    = useState('#7c3aed');
  const [speed,     setSpeed]     = useState(6);
  // BG opacity stored reversed: slider value = 60 - opacity*100
  const [bgSlider,  setBgSlider]  = useState(40);
  const [vizMode,   setVizMode]   = useState<VizMode>('ellipse');
  const [fifoPath,  setFifoPath]  = useState('/tmp/mpd.fifo');
  const [streamUrl, setStreamUrl] = useState('');

  // Populate form from store when panel opens
  useEffect(() => {
    if (!open) return;
    fetchSettings().then(s => {
      setSettings(s);
      setHost(s['mpd.host']            ?? 'localhost');
      setPort(s['mpd.port']            ?? '6600');
      setMusicDir(s['music.dir']       ?? '~/Music');
      setPauseOnClose(s['player.pauseOnClose'] === 'true');
      setAccent(s['ui.accentColor']    ?? '#7c3aed');
      setSpeed(parseInt(s['ui.vinylSpeed'] ?? '6'));
      const op = parseFloat(s['ui.bgOpacity'] ?? '0.20');
      setBgSlider(Math.round(Math.max(0, Math.min(60, 60 - op * 100))));
      setVizMode((s['visualizer.mode'] ?? 'ellipse') as VizMode);
      setFifoPath(s['fifo.path']       ?? '/tmp/mpd.fifo');
      setStreamUrl(s['stream.url']     ?? '');
    });
  }, [open]);

  // ── Persist helpers ──────────────────────────────────────────────────────

  const persist = useCallback((updates: Record<string, string>) => {
    patchSettings(updates);
    saveSettings(updates);
  }, [patchSettings]);

  const saveConn = useCallback(async () => {
    persist({ 'mpd.host': host, 'mpd.port': port, 'music.dir': musicDir });
    setConnStatus(null);
    try {
      const res = await testConnection(host, port);
      setConnStatus({ ok: res.ok, msg: res.ok ? `Connected — state: ${res.state}` : res.error ?? 'Failed' });
    } catch {
      setConnStatus({ ok: false, msg: 'Network error' });
    }
  }, [host, port, musicDir, persist]);

  const onAccentChange = useCallback((hex: string) => {
    setAccent(hex);
    applyAccent(hex);
    persist({ 'ui.accentColor': hex });
  }, [persist]);

  const onSpeedChange = useCallback((v: number) => {
    setSpeed(v);
    document.documentElement.style.setProperty('--vinyl-speed', `${v}s`);
    persist({ 'ui.vinylSpeed': String(v) });
  }, [persist]);

  const onBgChange = useCallback((v: number) => {
    setBgSlider(v);
    const opacity = (60 - v) / 100;
    document.documentElement.style.setProperty('--bg-opacity', String(opacity));
    persist({ 'ui.bgOpacity': opacity.toFixed(2) });
  }, [persist]);

  const onVizMode = useCallback((m: VizMode) => {
    setVizMode(m);
    persist({ 'visualizer.mode': m });
  }, [persist]);

  const onPauseToggle = useCallback(() => {
    const next = !pauseOnClose;
    setPauseOnClose(next);
    persist({ 'player.pauseOnClose': String(next) });
  }, [pauseOnClose, persist]);

  if (!open) return null;

  return (
    <>
      <div className="settings-backdrop open" onClick={() => setOpen(false)} />
      <div className="settings-panel open">
        <div className="settings-header">
          <span><i className="fas fa-gear" /> Settings</span>
          <button className="icon-btn" onClick={() => setOpen(false)}>
            <i className="fas fa-xmark" />
          </button>
        </div>

        <div className="settings-body">

          {/* ── Connection ── */}
          <div className="settings-section">
            <h3>Connection</h3>
            <div className="settings-field">
              <label>MPD host</label>
              <input type="text" value={host} onChange={e => setHost(e.target.value)} placeholder="localhost" />
            </div>
            <div className="settings-field">
              <label>MPD port</label>
              <input type="number" value={port} onChange={e => setPort(e.target.value)} placeholder="6600" min={1} max={65535} />
            </div>
            <div className="settings-field">
              <label>Music directory</label>
              <input type="text" value={musicDir} onChange={e => setMusicDir(e.target.value)} placeholder="~/Music" />
            </div>
            <div className="settings-actions">
              <button className="btn-secondary" onClick={saveConn}>
                <i className="fas fa-floppy-disk" /> Save &amp; Test
              </button>
            </div>
            {connStatus && (
              <div className={`conn-status${connStatus.ok ? ' ok' : ' err'}`}>
                {connStatus.ok ? '✓' : '✗'} {connStatus.msg}
              </div>
            )}
          </div>

          {/* ── Playback ── */}
          <div className="settings-section">
            <h3>Playback</h3>
            <div className="settings-row">
              <label>Pause on close</label>
              <Toggle on={pauseOnClose} onClick={onPauseToggle} />
            </div>
          </div>

          {/* ── Appearance ── */}
          <div className="settings-section">
            <h3>Appearance</h3>
            <div className="settings-field">
              <label>Accent colour</label>
              <input type="color" value={accent} onChange={e => onAccentChange(e.target.value)} />
            </div>
            <div className="settings-field">
              <label>Vinyl speed</label>
              <div className="slider-row">
                <input type="range" min={2} max={20} step={1} value={speed}
                       onChange={e => onSpeedChange(parseInt(e.target.value))} />
                <span>{speed} s/rev</span>
              </div>
            </div>
            <div className="settings-field">
              <label>Background opacity</label>
              <div className="slider-row">
                <input type="range" min={0} max={60} step={2} value={bgSlider}
                       onChange={e => onBgChange(parseInt(e.target.value))} />
                <span>{bgSlider} %</span>
              </div>
            </div>
          </div>

          {/* ── Visualizer ── */}
          <div className="settings-section">
            <h3>Visualizer</h3>
            <div className="settings-field">
              <label>Mode</label>
              <div className="viz-mode-btns">
                {(['ellipse', 'bar', 'off'] as VizMode[]).map(m => (
                  <button
                    key={m}
                    className={`viz-mode-btn${vizMode === m ? ' active' : ''}`}
                    onClick={() => onVizMode(m)}
                  >
                    <i className={`fas ${m === 'ellipse' ? 'fa-circle-notch' : m === 'bar' ? 'fa-chart-simple' : 'fa-ban'}`} />
                    {' '}{m.charAt(0).toUpperCase() + m.slice(1)}
                  </button>
                ))}
              </div>
            </div>
            <div className="settings-field">
              <label>FIFO path <span style={{ color: 'var(--text-dim)', fontSize: 10 }}>(same source as ncmpcpp)</span></label>
              <input type="text" value={fifoPath}
                     onChange={e => setFifoPath(e.target.value)}
                     onBlur={e  => persist({ 'fifo.path': e.target.value })}
                     placeholder="/tmp/mpd.fifo" />
            </div>
            <div className="settings-field">
              <label>Stream URL <span style={{ color: 'var(--text-dim)', fontSize: 10 }}>(Web Audio fallback)</span></label>
              <input type="text" value={streamUrl}
                     onChange={e => setStreamUrl(e.target.value)}
                     onBlur={e  => persist({ 'stream.url': e.target.value })}
                     placeholder="http://localhost:8000" />
            </div>
          </div>

        </div>
      </div>
    </>
  );
}
