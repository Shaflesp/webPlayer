import { useState, useEffect, useRef, useCallback } from 'react';
import { useStore } from '../store';
import { addUri } from '../api';

// ── API types ─────────────────────────────────────────────────────────────────

interface PlaylistEntry {
  name:        string;
  url:         string;   // stored from the first import
  lastSynced:  string;   // "2024-12-01 14:30:00" or ""
  tracks:      number;
}

async function apiStartSync(url: string): Promise<{ jobId: string }> {
  const r = await fetch('/SyncServlet', {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ url }),
  });
  return r.json();
}

async function apiFetchPlaylists(): Promise<PlaylistEntry[]> {
  return fetch('/SyncServlet?action=list').then(r => r.json());
}

// ── SyncPanel ─────────────────────────────────────────────────────────────────

export function SyncPanel() {
  const open    = useStore(s => s.syncOpen);
  const setOpen = useStore(s => s.setSyncOpen);

  const [url,       setUrl]       = useState('');
  const [syncing,   setSyncing]   = useState(false);
  const [lines,     setLines]     = useState<string[]>([]);
  const [progress,  setProgress]  = useState<{ n: number; total: number } | null>(null);
  const [doneState, setDoneState] = useState<'ok' | 'error' | null>(null);
  const [playlists, setPlaylists] = useState<PlaylistEntry[]>([]);

  const logRef  = useRef<HTMLDivElement>(null);
  const esRef   = useRef<EventSource | null>(null);

  // Load playlist list when panel opens
  useEffect(() => {
    if (!open) return;
    apiFetchPlaylists().then(setPlaylists).catch(() => {});
  }, [open]);

  // Auto-scroll log
  useEffect(() => {
    if (logRef.current)
      logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [lines]);

  // Parse "Downloading item X of Y" for progress bar
  useEffect(() => {
    const last = lines.at(-1) ?? '';
    const m    = last.match(/Downloading item (\d+) of (\d+)/);
    if (m) setProgress({ n: parseInt(m[1]), total: parseInt(m[2]) });
  }, [lines]);

  // Cleanup SSE on unmount
  useEffect(() => () => { esRef.current?.close(); }, []);

  // ── Start sync (used both by the URL input and the re-sync buttons) ──────
  const handleSync = useCallback(async (syncUrl: string) => {
    if (!syncUrl.trim() || syncing) return;
    setSyncing(true);
    setLines([]);
    setProgress(null);
    setDoneState(null);

    try {
      const { jobId } = await apiStartSync(syncUrl.trim());
      const es = new EventSource(`/SyncServlet?action=stream&jobId=${jobId}`);
      esRef.current = es;

      es.onmessage = e => setLines(prev => [...prev, e.data as string]);

      es.addEventListener('done', e => {
        const ok = (e as MessageEvent<string>).data === 'ok';
        setDoneState(ok ? 'ok' : 'error');
        setSyncing(false);
        es.close();
        // Refresh list — CSV was updated server-side automatically
        if (ok) apiFetchPlaylists().then(setPlaylists).catch(() => {});
      });

      es.onerror = () => {
        setSyncing(false);
        setDoneState('error');
        es.close();
      };
    } catch {
      setLines(['Network error — is the server running?']);
      setSyncing(false);
      setDoneState('error');
    }
  }, [syncing]);

  if (!open) return null;

  const pct = progress ? Math.round((progress.n / progress.total) * 100) : 0;

  return (
      <>
        <div className="settings-backdrop open" onClick={() => setOpen(false)} />

        <div className="settings-panel open" style={{ width: 460 }}>
          <div className="settings-header">
            <span><i className="fas fa-cloud-arrow-down" /> Import from YouTube</span>
            <button className="icon-btn" onClick={() => setOpen(false)}>
              <i className="fas fa-xmark" />
            </button>
          </div>

          <div className="settings-body">

            {/* ── URL input ── */}
            <div className="settings-section">
              <h3>Sync new playlist</h3>
              <div className="settings-field">
                <label>YouTube playlist URL</label>
                <input
                    type="text"
                    placeholder="https://www.youtube.com/playlist?list=…"
                    value={url}
                    onChange={e => setUrl(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && handleSync(url)}
                    disabled={syncing}
                />
              </div>
              <button
                  className="btn-primary"
                  onClick={() => handleSync(url)}
                  disabled={syncing || !url.trim()}
                  style={{ opacity: syncing || !url.trim() ? .5 : 1, width: '100%' }}
              >
                {syncing
                    ? <><i className="fas fa-spinner fa-spin" /> Syncing…</>
                    : <><i className="fas fa-cloud-arrow-down" /> Sync</>}
              </button>

              {/* Progress bar */}
              {progress && (
                  <div style={{ marginTop: 14 }}>
                    <div style={{
                      display: 'flex', justifyContent: 'space-between',
                      fontSize: 11, color: 'var(--text-sub)', marginBottom: 5,
                    }}>
                      <span>{progress.n} / {progress.total} tracks</span>
                      <span>{pct}%</span>
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
                          color: l.startsWith('ERROR') || l.startsWith('✗') ? '#f87171'
                              : l.startsWith('✓')                          ? '#4ade80'
                                  : l.startsWith('Warning')                    ? '#facc15'
                                      : undefined,
                        }}>{l}</div>
                    ))}
                  </div>
              )}

              {/* Result */}
              {doneState && (
                  <div style={{
                    marginTop: 10, fontSize: 13,
                    color: doneState === 'ok' ? '#4ade80' : '#f87171',
                  }}>
                    {doneState === 'ok'
                        ? <><i className="fas fa-check" /> Sync complete — library updated automatically.</>
                        : <><i className="fas fa-triangle-exclamation" /> Sync failed — check the log above.</>}
                  </div>
              )}
            </div>

            {/* ── Synced playlists ── */}
            {playlists.length > 0 && (
                <div className="settings-section">
                  <h3>Synced playlists</h3>
                  {playlists.map(p => (
                      <PlaylistRow
                          key={p.name}
                          entry={p}
                          syncing={syncing}
                          onResync={() => handleSync(p.url)}
                          onAdd={() => addUri(p.name)}
                      />
                  ))}
                </div>
            )}

          </div>
        </div>
      </>
  );
}

// ── Playlist row ──────────────────────────────────────────────────────────────

function PlaylistRow({
                       entry, syncing, onResync, onAdd,
                     }: {
  entry: PlaylistEntry;
  syncing: boolean;
  onResync: () => void;
  onAdd: () => void;
}) {
  const hasUrl = !!entry.url;

  return (
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10,
        padding: '10px 0', borderBottom: '1px solid var(--border)',
      }}>
        <i className="fas fa-music"
           style={{ color: 'var(--accent)', fontSize: 14, flexShrink: 0 }} />

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            fontSize: 13, fontWeight: 500,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>{entry.name}</div>
          <div style={{ fontSize: 11, color: 'var(--text-sub)', marginTop: 1 }}>
            {entry.tracks} track{entry.tracks !== 1 ? 's' : ''}
            {entry.lastSynced && (
                <span style={{ marginLeft: 8, color: 'var(--text-dim)' }}>
              · last synced {entry.lastSynced}
            </span>
            )}
          </div>
        </div>

        {/* Add to queue */}
        <button
            className="icon-btn"
            title="Add to queue"
            onClick={onAdd}
        >
          <i className="fas fa-circle-plus" />
        </button>

        {/* Re-sync — only enabled if we have the stored URL */}
        <button
            className="icon-btn"
            title={hasUrl ? 'Re-sync playlist' : 'URL not stored — use the input above'}
            onClick={onResync}
            disabled={!hasUrl || syncing}
            style={{ opacity: hasUrl && !syncing ? 1 : 0.35 }}
        >
          <i className="fas fa-arrows-rotate" />
        </button>
      </div>
  );
}