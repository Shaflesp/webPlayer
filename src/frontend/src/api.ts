import type { MPDSong, MPDItem, MPDStatus, AppSettings, DependencyStatus } from './types';

// ── MPDServlet ────────────────────────────────────────────────────────────────

async function mpdGet<T>(action: string, params: Record<string, string> = {}): Promise<T> {
    const qs = new URLSearchParams({ action, ...params });
    const r  = await fetch(`/MPDServlet?${qs}`);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json() as Promise<T>;
}

/** Silent — fire-and-forget for actions that essentially never fail in practice (play/pause/etc). */
async function mpdPost(action: string, params: Record<string, unknown> = {}): Promise<void> {
    await fetch('/MPDServlet', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ action, ...params }),
    });
}

/** Checked — throws on failure. Use for actions where the user needs to know if it didn't work. */
async function mpdPostChecked(action: string, params: Record<string, unknown> = {}): Promise<void> {
    const r = await fetch('/MPDServlet', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ action, ...params }),
    });
    if (!r.ok) {
        const body = await r.json().catch(() => null);
        throw new Error(body?.message ?? body?.error ?? `HTTP ${r.status}`);
    }
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

export const play        = ()              => mpdPost('play');
export const pause       = ()              => mpdPost('pause');
export const resume      = ()              => mpdPost('resume');
export const stop        = ()              => mpdPost('stop');
export const next        = ()              => mpdPost('next');
export const previous    = ()              => mpdPost('previous');
export const playId      = (id: number)    => mpdPost('playid',  { id });
export const deletePos   = (pos: number)   => mpdPost('delete',  { pos });
export const clearQueue  = ()              => mpdPost('clear');
export const seekCur     = (time: number)  => mpdPost('seek',    { time });
export const setVol      = (volume: number)=> mpdPost('setvol',  { volume });
export const addUri      = (uri: string)   => mpdPost('add',     { uri });
export const addPlay     = (uri: string)   => mpdPost('addplay', { uri });
export const moveQueue   = (from: number, to: number) => mpdPostChecked('move', { from, to });
export const toggleRandom  = () => mpdPost('toggle_random');
export const toggleRepeat  = () => mpdPost('toggle_repeat');
export const toggleSingle  = () => mpdPost('toggle_single');
export const updateDb      = () => mpdPost('update');

// ── MPD-native playlists (separate from the YouTube-sync playlist folders) ───

export interface PlaylistEntry { name: string; lastModified?: string; }

export const fetchPlaylists = (): Promise<PlaylistEntry[]> =>
    mpdGet<Array<Record<string, string>>>('playlists').then(arr =>
        arr.map(p => ({ name: p['playlist'], lastModified: p['Last-Modified'] })),
    );

export const fetchPlaylistSongs = (name: string): Promise<MPDSong[]> =>
    mpdGet<MPDSong[]>('playlistsongs', { name });

export const playlistLoad   = (name: string) => mpdPostChecked('playlistload', { name });
export const playlistSave   = (name: string) => mpdPostChecked('playlistsave', { name });
export const playlistDelete = (name: string) => mpdPostChecked('playlistrm',   { name });

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

// ── StatusServlet ─────────────────────────────────────────────────────────────

export const fetchDependencyStatus = (): Promise<DependencyStatus> =>
    fetch('/StatusServlet').then(r => r.json());

// ── Helpers ───────────────────────────────────────────────────────────────────

export const artUrl = (file: string) =>
    `/ArtServlet?uri=${encodeURIComponent(file)}`;

export const basename = (path: string): string =>
    path.split('/').pop()?.replace(/\.[^.]+$/, '') ?? '';

// ── SyncServlet (YouTube playlist sync) ───────────────────────────────────────

export interface SyncedPlaylist { name: string; url: string; lastSynced: string; tracks: number; }

export const startSync = (url: string): Promise<{ jobId: string }> =>
    fetch('/SyncServlet', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ url }),
    }).then(r => r.json());

export const fetchSyncedPlaylists = (): Promise<SyncedPlaylist[]> =>
    fetch('/SyncServlet?action=list').then(r => r.json());

export const triggerMpdUpdate = (): Promise<void> =>
    fetch('/SyncServlet', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ action: 'updateMPD' }),
    }).then(() => undefined);