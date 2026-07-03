export interface MPDStatus {
  state:           'play' | 'pause' | 'stop';
  elapsed:         number;
  duration:        number;
  volume:          number;
  random:          number;
  repeat:          number;
  single:          number;
  consume:         number;
  songid:          number;
  playlistlength:  number;
  playlistversion: number;
  bitrate:         number;
}

export interface MPDSong {
  file?:        string;
  Title?:       string;
  Artist?:      string;
  Album?:       string;
  AlbumArtist?: string;
  Time?:        string;
  Id?:          string;
  Pos?:         string;
  [key: string]: string | undefined;
}

/** Item returned by lsinfo (file, directory, or playlist entry) */
export interface MPDItem {
  _type?:    'file' | 'directory' | 'playlist';
  file?:     string;
  directory?: string;
  playlist?: string;
  Title?:    string;
  Artist?:   string;
  AlbumArtist?: string;
  Album?:    string;
  Time?:     string;
  [key: string]: string | undefined;
}

export interface DependencyStatus {
  ytdlp:  { ok: boolean; path: string; note: string; version: string };
  mpd:    { ok: boolean };
  ffmpeg: { ok: boolean };
}

export type AppSettings = Record<string, string>;

export type SidebarTab = 'queue' | 'library' | 'search' | 'playlists';
export type VizMode    = 'ellipse' | 'bar' | 'off';