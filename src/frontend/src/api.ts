import type { MPDSong, MPDItem, MPDStatus, AppSettings } from './types';

// ── MPDServlet ────────────────────────────────────────────────────────────────

async function mpdGet<T>(action: string, params: Record<string, string> = {}): Promise<T> {
  const qs = new URLSearchParams({ action, ...params });
  const r  = await fetch(`/MPDServlet?${qs}`);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json() as Promise<T>;
}

async function mpdPost(action: string, params: Record<string, unknown> = {}): Promise<void> {
  await fetch('/MPDServlet', {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ action, ...params }),
  });
}

export const fetchNowPlaying = () =>
  mpdGet<{ status: MPDStatus; song: MPDSong }>('nowplaying');

export const fetchQueue = () =>
  mpdGet<MPDSong[]>('queue');

export const fetchBrowse = (uri: string) =>
  mpdGet<MPDItem[]>('browse', { uri });

export const fetchSearch = (q: string) =>
  mpdGet<MPDSong[]>('search', { q });

// Transport
export const cmd = mpdPost;

// Convenience wrappers so callers don't have to remember action strings
export const play        = ()           => mpdPost('play');
export const pause       = ()           => mpdPost('pause');
export const resume      = ()           => mpdPost('resume');
export const stop        = ()           => mpdPost('stop');
export const next        = ()           => mpdPost('next');
export const previous    = ()           => mpdPost('previous');
export const playId      = (id: number) => mpdPost('playid',  { id });
export const deletePos   = (pos: number)=> mpdPost('delete',  { pos });
export const clearQueue  = ()           => mpdPost('clear');
export const seekCur     = (time: number) => mpdPost('seek',  { time });
export const setVol      = (volume: number) => mpdPost('setvol', { volume });
export const addUri      = (uri: string)    => mpdPost('add',    { uri });
export const addPlay     = (uri: string)    => mpdPost('addplay',{ uri });
export const toggleRandom  = () => mpdPost('toggle_random');
export const toggleRepeat  = () => mpdPost('toggle_repeat');
export const toggleSingle  = () => mpdPost('toggle_single');
export const updateDb      = () => mpdPost('update');

// ── ConfigServlet ─────────────────────────────────────────────────────────────

export const fetchSettings = (): Promise<AppSettings> =>
  fetch('/ConfigServlet').then(r => r.json());

export const saveSettings = (updates: Partial<AppSettings>): Promise<void> =>
  fetch('/ConfigServlet', {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(updates),
  }).then(() => undefined);

export const testConnection = (host: string, port: string) =>
  fetch(`/ConfigServlet?action=test&host=${encodeURIComponent(host)}&port=${encodeURIComponent(port)}`)
    .then(r => r.json() as Promise<{ ok: boolean; state?: string; error?: string }>);

// ── Helpers ───────────────────────────────────────────────────────────────────

export const artUrl = (file: string) =>
  `/ArtServlet?uri=${encodeURIComponent(file)}`;

export const basename = (path: string): string =>
  path.split('/').pop()?.replace(/\.[^.]+$/, '') ?? '';
