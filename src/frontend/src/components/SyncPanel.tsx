import { useState, useEffect, useRef, useCallback } from 'react';
import { useStore } from '../store';
import {
  startSync, fetchSyncedPlaylists, triggerMpdUpdate,
  addUri, type SyncedPlaylist,
} from '../api';

export function SyncPanel() {
  const open    = useStore(s => s.syncOpen);
  const setOpen = useStore(s => s.setSyncOpen);

  // ── Form state ────────────────────────────────────────────────────────────
  const [url,      setUrl]      = useState('');
  const [syncing,  setSyncing]  = useState(false);
  const [lines,    setLines]    = useState<string[]>([]);
  const [progress, setProgress] = useState<{ n: number; total: number } | null>(null);
  const [playlist, setPlaylist] = useState<string | null>(null);
  const [done,     setDone]     = useState(false);
  const [success,  setSuccess]  = useState(false);
  const [playlists, setPlaylists] = useState<SyncedPlaylist[]>([]);

  const logRef = useRef<HTMLDivElement>(null);
  const esRef  = useRef<EventSource | null>(null);

  // ── Load existing playlists when panel opens ──────────────────────────────
  useEffect(() => {
    if (!open) return;
    fetchSyncedPlaylists().then(setPlaylists).catch(() => {});
  }, [open]);

  // ── Auto-scroll log ───────────────────────────────────────────────────────
  useEffect(() => {
    if (logRef.current)
      logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [lines]);

  // ── Parse "Downloading item X of Y" for the progress bar ─────────────────
  useEffect(() => {
    const last = lines.at(-1) ?? '';
    const m = last.match(/Downloading item (\d+) of (\d+)/);
    if (m) setProgress({ n: parseInt(m[1]), total: parseInt(m[2]) });
  }, [lines]);

  // ── Cleanup SSE on unmount ────────────────────────────────────────────────
  useEffect(() => () => { esRef.current?.close(); }, []);

  // ── Start sync ────────────────────────────────────────────────────────────
  const handleSync = useCallback(async () => {
    if (!url.trim() || syncing) return;
    setSyncing(true);
    setLines([]);
    setProgress(null);
    setPlaylist(null);
    setDone(false);
    setSuccess(false);

    try {
      const { jobId } = await startSync(url.trim());
      const es = new EventSource(`/SyncServlet?action=stream&jobId=${jobId}`);
      esRef.current = es;

      es.onmessage = e =>
        setLines(prev => [...prev, e.data as string]);

      es.addEventListener('playlist', e =>
        setPlaylist((e as MessageEvent<string>).data));

      es.addEventListener('done', e => {
        const ok = (e as MessageEvent<string>).data === 'ok';
        setSuccess(ok);
        setDone(true);
        setSyncing(false);
        es.close();
        if (ok) fetchSyncedPlaylists().then(setPlaylists).catch(() => {});
      });

      es.onerror = () => {
        setDone(true);
        setSyncing(false);
        es.close();
      };
    } catch {
      setLines(['Network error — is Tomcat running?']);
      setSyncing(false);
    }
  }, [url, syncing]);

  if (!open) return null;

  const pct = progress ? Math.round((progress.n / progress.total) * 100) : 0;

  return (
    <>
      <div className="settings-backdrop open" onClick={() => setOpen(false)} />

      <div className="settings-panel open" style={{ width: 440 }}>
        <div className="settings-header">
          <span><i className="fas fa-cloud-arrow-down" /> Import from YouTube</span>
          <button className="icon-btn" onClick={() => setOpen(false)}>
            <i className="fas fa-xmark" />
          </button>
        </div>

        <div className="settings-body">

          {/* ── URL input ── */}
          <div className="settings-section">
            <h3>Sync playlist</h3>
            <div className="settings-field">
              <label>YouTube playlist URL</label>
              <input
                type="text"
                placeholder="https://www.youtube.com/playlist?list=…"
                value={url}
                onChange={e => setUrl(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleSync()}
                disabled={syncing}
              />
            </div>
            <div className="settings-actions">
              <button
                className="btn-primary"
                onClick={handleSync}
                disabled={syncing || !url.trim()}
                style={{ opacity: syncing || !url.trim() ? .5 : 1 }}
              >
                {syncing
                  ? <><i className="fas fa-spinner fa-spin" /> Syncing…</>
                  : <><i className="fas fa-cloud-arrow-down" /> Sync</>}
              </button>
            </div>

            {/* Progress bar */}
            {progress && (
              <div style={{ marginTop: 12 }}>
                <div style={{
                  display: 'flex', justifyContent: 'space-between',
                  fontSize: 11, color: 'var(--text-sub)', marginBottom: 5,
                }}>
                  <span style={{ overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap', flex:1 }}>
                    {playlist ?? 'Downloading…'}
                  </span>
                  <span style={{ flexShrink: 0, marginLeft: 8 }}>
                    {progress.n} / {progress.total} ({pct}%)
                  </span>
                </div>
                <div style={{ height: 4, background: 'var(--bg-card)', borderRadius: 99 }}>
                  <div style={{
                    height: '100%', borderRadius: 99, background: 'var(--accent)',
                    width: `${pct}%`, transition: 'width .4s',
                  }} />
                </div>
              </div>
            )}

            {/* Log output */}
            {lines.length > 0 && (
              <div
                ref={logRef}
                style={{
                  marginTop: 12, height: 180, overflowY: 'auto',
                  background: '#0d0d0f', borderRadius: 8,
                  padding: '8px 10px', fontFamily: 'monospace',
                  fontSize: 11, color: '#a1a1aa',
                  border: '1px solid var(--border)',
                  whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                }}
              >
                {lines.map((l, i) => (
                  <div key={i} style={{
                    color: l.startsWith('ERROR') || l.startsWith('✗')
                      ? '#f87171'
                      : l.startsWith('✓') ? '#4ade80'
                      : l.startsWith('Warning') ? '#facc15'
                      : undefined,
                  }}>{l}</div>
                ))}
              </div>
            )}

            {/* Done state */}
            {done && (
              <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <span style={{
                  fontSize: 13, flex: 1,
                  color: success ? '#4ade80' : '#f87171',
                }}>
                  {success
                    ? <><i className="fas fa-check" /> Sync complete!</>
                    : <><i className="fas fa-triangle-exclamation" /> Sync failed — check the log</>}
                </span>
                {success && (
                  <>
                    <button className="btn-secondary" onClick={triggerMpdUpdate}>
                      <i className="fas fa-arrows-rotate" /> Update library
                    </button>
                    {playlist && (
                      <button className="btn-secondary" onClick={() => addUri(playlist)}>
                        <i className="fas fa-plus" /> Add to queue
                      </button>
                    )}
                  </>
                )}
              </div>
            )}
          </div>

          {/* ── Existing playlists ── */}
          {playlists.length > 0 && (
            <div className="settings-section">
              <h3>Synced playlists</h3>
              {playlists.map(p => (
                <div key={p.name} style={{
                  display: 'flex', alignItems: 'center', gap: 10,
                  padding: '9px 0', borderBottom: '1px solid var(--border)',
                }}>
                  <i className="fas fa-music"
                     style={{ color: 'var(--accent)', fontSize: 13, flexShrink: 0 }} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{
                      fontSize: 13, fontWeight: 500,
                      overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                    }}>{p.name}</div>
                    <div style={{ fontSize: 11, color: 'var(--text-sub)' }}>
                      {p.tracks} track{p.tracks !== 1 ? 's' : ''}
                    </div>
                  </div>
                  <button
                    className="icon-btn"
                    title="Add to queue"
                    onClick={() => addUri(p.name)}
                  ><i className="fas fa-plus" /></button>
                  <button
                    className="icon-btn"
                    title="Re-sync (paste the playlist URL above and click Sync)"
                    onClick={() => {
                      setUrl('');
                      setLines([`Re-syncing "${p.name}": paste the YouTube URL above and click Sync.`]);
                      setDone(false);
                    }}
                  ><i className="fas fa-arrows-rotate" /></button>
                </div>
              ))}
            </div>
          )}

        </div>
      </div>
    </>
  );
}
