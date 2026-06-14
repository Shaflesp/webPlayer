import { useState, useEffect, memo, useCallback, useRef } from 'react';
import { useStore } from '../store';
import { Tooltip } from './Tooltip';
import {
  playId, deletePos, clearQueue, addUri, addPlay,
  fetchBrowse, fetchSearch, artUrl, basename, updateDb,
} from '../api';
import type { MPDSong, MPDItem, SidebarTab } from '../types';

// ── Queue ─────────────────────────────────────────────────────────────────────

interface QueueItemProps { song: MPDSong; pos: number; isPlaying: boolean; }

const QueueItem = memo(({ song, pos, isPlaying }: QueueItemProps) => {
  const url   = song.file ? artUrl(song.file) : null;
  const title  = song.Title  ?? (song.file ? basename(song.file) : '?');
  const artist = song.Artist ?? song.AlbumArtist ?? '—';

  return (
      <div
          className={`list-item${isPlaying ? ' playing' : ''}`}
          onClick={() => playId(parseInt(song.Id ?? '0'))}
      >
        <div className="item-thumb">
          {url && <img src={url} alt="" onError={e => (e.currentTarget.style.display = 'none')} />}
          <div className="thumb-fallback">
            {isPlaying
                ? <i className="fas fa-volume-high" />
                : <span className="item-num">{pos + 1}</span>}
          </div>
          {isPlaying && <div className="playing-overlay"><i className="fas fa-volume-high" /></div>}
        </div>
        <div className="item-info">
          <span className="item-title">{title}</span>
          <span className="item-sub">{artist}</span>
        </div>
        <button
            className="item-remove"
            onClick={e => { e.stopPropagation(); deletePos(pos); }}
        >
          <i className="fas fa-xmark" />
        </button>
      </div>
  );
});
QueueItem.displayName = 'QueueItem';

function QueueTab() {
  const queue   = useStore(s => s.queue);
  const songid  = useStore(s => s.status.songid);
  const listRef = useRef<HTMLDivElement>(null);

  // Scroll playing item into view whenever it changes
  useEffect(() => {
    listRef.current?.querySelector('.playing')
        ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [songid]);

  return (
      <div className="scroll-list" ref={listRef}>
        {queue.length === 0
            ? <div className="empty-msg"><i className="fas fa-list-ul" /><br />Queue is empty</div>
            : queue.map((song, i) => (
                <QueueItem
                    key={song.Id ?? i}
                    song={song}
                    pos={i}
                    isPlaying={song.Id === String(songid)}
                />
            ))}
      </div>
  );
}

// ── Library ───────────────────────────────────────────────────────────────────

interface LibItemProps { item: MPDItem; onNavigate: (uri: string) => void; }

const LibItem = memo(({ item, onNavigate }: LibItemProps) => {
  const type  = item._type;
  const file  = item.file ?? '';
  const title = item.Title ?? (file ? basename(file) : item.directory?.split('/').pop() ?? '?');
  const artist = item.Artist ?? item.AlbumArtist ?? '';
  const url   = type === 'file' && file ? artUrl(file) : null;

  if (type === 'directory') {
    const name = item.directory?.split('/').pop() ?? item.directory ?? '?';
    return (
        <div className="list-item" onClick={() => onNavigate(item.directory ?? '')}>
          <div className="item-thumb"><i className="fas fa-folder" /></div>
          <div className="item-info"><span className="item-title">{name}</span></div>
          <i className="fas fa-chevron-right item-chevron" />
        </div>
    );
  }

  if (type === 'file') {
    return (
        <div className="list-item" onDoubleClick={() => addPlay(file)}>
          <div className="item-thumb">
            {url && <img src={url} alt="" onError={e => (e.currentTarget.style.display = 'none')} />}
            <div className="thumb-fallback"><i className="fas fa-music" /></div>
          </div>
          <div className="item-info">
            <span className="item-title">{title}</span>
            {artist && <span className="item-sub">{artist}</span>}
          </div>
          <button
              className="item-add"
              onClick={e => { e.stopPropagation(); addUri(file); }}
          ><i className="fas fa-plus" /></button>
        </div>
    );
  }

  return null;
});
LibItem.displayName = 'LibItem';

function LibraryTab() {
  const [uri,   setUri]   = useState('');
  const [stack, setStack] = useState<string[]>([]);
  const [items, setItems] = useState<MPDItem[]>([]);

  const load = useCallback((newUri: string) => {
    setUri(newUri);
    fetchBrowse(newUri).then(setItems).catch(() => setItems([]));
  }, []);

  useEffect(() => { load(''); }, [load]);

  const navigate = useCallback((newUri: string) => {
    setStack(s => [...s, uri]);
    load(newUri);
  }, [uri, load]);

  const back = useCallback(() => {
    const prev = stack[stack.length - 1] ?? '';
    setStack(s => s.slice(0, -1));
    load(prev);
  }, [stack, load]);

  const dirs  = items.filter(i => i._type === 'directory');
  const files = items.filter(i => i._type === 'file');
  const label = uri ? basename(uri) || uri.split('/').pop() || 'Library' : 'Library';

  return (
      <>
        <div className="tab-toolbar">
          {uri && (
              <Tooltip text="Go back">
                <button className="icon-btn" onClick={back}><i className="fas fa-arrow-left" /></button>
              </Tooltip>
          )}
          <span className="toolbar-label">{label}</span>
          {uri && (
              <Tooltip text="Add all to queue">
                <button className="icon-btn" onClick={() => addUri(uri)}>
                  <i className="fas fa-circle-plus" />
                </button>
              </Tooltip>
          )}
          <Tooltip text="Update database">
            <button className="icon-btn" onClick={updateDb}><i className="fas fa-arrows-rotate" /></button>
          </Tooltip>
        </div>
        <div className="scroll-list">
          {items.length === 0 ? (
              <div className="empty-msg">
                <i className="fas fa-database" /><br />Library is empty<br />
                <button className="update-db-btn" onClick={updateDb}>
                  <i className="fas fa-arrows-rotate" /> Update database
                </button>
              </div>
          ) : (
              [...dirs, ...files].map((item, i) => (
                  <LibItem key={item.file ?? item.directory ?? i} item={item} onNavigate={navigate} />
              ))
          )}
        </div>
      </>
  );
}

// ── Search ────────────────────────────────────────────────────────────────────

function SearchTab() {
  const [query,   setQuery]   = useState('');
  const [results, setResults] = useState<MPDSong[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout>>();

  const onInput = (q: string) => {
    setQuery(q);
    clearTimeout(timer.current);
    if (!q.trim()) { setResults([]); return; }
    timer.current = setTimeout(() => {
      fetchSearch(q).then(setResults).catch(() => setResults([]));
    }, 350);
  };

  return (
      <>
        <div className="search-box">
          <i className="fas fa-magnifying-glass" />
          <input
              type="text"
              placeholder="Search title, artist, album…"
              value={query}
              onChange={e => onInput(e.target.value)}
              autoComplete="off"
          />
          {query && (
              <button className="clear-btn" onClick={() => { setQuery(''); setResults([]); }}>
                <i className="fas fa-xmark" />
              </button>
          )}
        </div>
        <div className="scroll-list">
          {query && results.length === 0 && <div className="empty-msg">No results</div>}
          {results.map((song, i) => {
            const file   = song.file ?? '';
            const title  = song.Title  ?? basename(file);
            const artist = song.Artist ?? song.AlbumArtist ?? '—';
            const sub    = [artist, song.Album].filter(Boolean).join(' · ');
            const url    = file ? artUrl(file) : null;
            return (
                <div key={song.file ?? i} className="list-item" onDoubleClick={() => addPlay(file)}>
                  <div className="item-thumb">
                    {url && <img src={url} alt="" onError={e => (e.currentTarget.style.display = 'none')} />}
                    <div className="thumb-fallback"><i className="fas fa-music" /></div>
                  </div>
                  <div className="item-info">
                    <span className="item-title">{title}</span>
                    <span className="item-sub">{sub}</span>
                  </div>
                  <button
                      className="item-add"
                      onClick={e => { e.stopPropagation(); addUri(file); }}
                  ><i className="fas fa-plus" /></button>
                </div>
            );
          })}
        </div>
      </>
  );
}

// ── Sidebar shell ─────────────────────────────────────────────────────────────

const TABS: { id: SidebarTab; label: string; icon: string }[] = [
  { id: 'queue',   label: 'Queue',   icon: 'fa-list-ul'       },
  { id: 'library', label: 'Library', icon: 'fa-folder-open'   },
  { id: 'search',  label: 'Search',  icon: 'fa-magnifying-glass' },
];

export function Sidebar() {
  const tab    = useStore(s => s.sidebarTab);
  const queue  = useStore(s => s.queue);
  const setTab = useStore(s => s.setSidebarTab);
  const setSettingsOpen = useStore(s => s.setSettingsOpen);
  const setSyncOpen     = useStore(s => s.setSyncOpen);

  return (
      <aside className="sidebar">
        <div className="sidebar-header">
          <div className="brand">
            <img src="/images/icon.png" alt="" className="brand-logo"
                 onError={e => (e.currentTarget.style.display = 'none')} />
            <span>WebPlayer</span>
            <Tooltip text="Import from YouTube">
              <button className="icon-btn" onClick={() => setSyncOpen(true)}>
                <i className="fas fa-cloud-arrow-down" />
              </button>
            </Tooltip>
            <Tooltip text="Settings">
              <button className="icon-btn" onClick={() => setSettingsOpen(true)}>
                <i className="fas fa-gear" />
              </button>
            </Tooltip>
          </div>

          <div className="tab-bar">
            {TABS.map(t => (
                <button
                    key={t.id}
                    className={`tab${tab === t.id ? ' active' : ''}`}
                    onClick={() => setTab(t.id)}
                >
                  <i className={`fas ${t.icon}`} /> {t.label}
                </button>
            ))}
          </div>
        </div>

        {/* Queue tab */}
        {tab === 'queue' && (
            <>
              <div className="tab-toolbar">
                <span className="toolbar-label">Queue ({queue.length})</span>
                <Tooltip text="Clear queue">
                  <button className="icon-btn" onClick={clearQueue}>
                    <i className="fas fa-trash-can" />
                  </button>
                </Tooltip>
              </div>
              <QueueTab />
            </>
        )}

        {/* Library tab */}
        {tab === 'library' && <LibraryTab />}

        {/* Search tab */}
        {tab === 'search' && <SearchTab />}
      </aside>
  );
}