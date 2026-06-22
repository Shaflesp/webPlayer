import { create } from 'zustand';
import type { MPDStatus, MPDSong, AppSettings, SidebarTab } from './types';

// ── Defaults ─────────────────────────────────────────────────────────────────

const DEFAULT_STATUS: MPDStatus = {
  state: 'stop', elapsed: 0, duration: 0,
  volume: 100, random: 0, repeat: 0, single: 0, consume: 0,
  songid: -1, playlistlength: 0, playlistversion: -1, bitrate: 0,
};

export const DEFAULT_SETTINGS: AppSettings = {
  'mpd.host':            'localhost',
  'mpd.port':            '6600',
  'music.dir':           '~/Music',
  'ui.accentColor':      '#7c3aed',
  'ui.vinylSpeed':       '6',
  'ui.bgOpacity':        '0.20',
  'player.pauseOnClose': 'false',
  'player.pollInterval': '1000',
  'stream.url':          '',
  'visualizer.mode':     'ellipse',
  'fifo.path':           '/tmp/mpd.fifo',
};

// ── Store interface ───────────────────────────────────────────────────────────

interface PlayerState {
  status:       MPDStatus;
  currentSong:  MPDSong;
  queue:        MPDSong[];
  settings:     AppSettings;
  sidebarTab:   SidebarTab;
  settingsOpen: boolean;
  syncOpen:     boolean;
  errorMsg:     string | null;
  depWarning:   string | null;

  setStatus:       (s: MPDStatus)            => void;
  setCurrentSong:  (s: MPDSong)              => void;
  setQueue:        (q: MPDSong[])            => void;
  setSettings:     (s: AppSettings)          => void;
  patchSettings:   (patch: Partial<AppSettings>) => void;
  setSidebarTab:   (t: SidebarTab)           => void;
  setSettingsOpen: (open: boolean)           => void;
  setSyncOpen:     (open: boolean)           => void;
  setError:        (msg: string | null)      => void;
  setDepWarning:   (msg: string | null)      => void;
}

// ── Store ─────────────────────────────────────────────────────────────────────

export const useStore = create<PlayerState>((set) => ({
  status:       DEFAULT_STATUS,
  currentSong:  {},
  queue:        [],
  settings:     DEFAULT_SETTINGS,
  sidebarTab:   'queue',
  settingsOpen: false,
  syncOpen:     false,
  errorMsg:     null,
  depWarning:   null,

  setStatus:       (status)       => set({ status }),
  setCurrentSong:  (currentSong)  => set({ currentSong }),
  setQueue:        (queue)        => set({ queue }),
  setSettings:     (settings)     => set({ settings }),
  patchSettings:   (patch)        => set(s => ({
    settings: { ...DEFAULT_SETTINGS, ...s.settings, ...patch } as AppSettings,
  })),
  setSidebarTab:   (sidebarTab)   => set({ sidebarTab }),
  setSettingsOpen: (settingsOpen) => set({ settingsOpen }),
  setSyncOpen:     (syncOpen)     => set({ syncOpen }),
  setError:        (errorMsg)     => set({ errorMsg }),
  setDepWarning:   (depWarning)   => set({ depWarning }),
}));