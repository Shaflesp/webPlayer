/* ═══════════════════════════════════════════════════════════════════════════
   WebPlayer — MPD control frontend
   ─────────────────────────────────────────────────────────────────────────
   The browser is a PURE CONTROL INTERFACE.
   MPD plays audio through the system (PipeWire/ALSA) — no audio element,
   no double output, no stream conflicts.

   Seek bar and elapsed time are driven by polling MPD's status every second.
   ═══════════════════════════════════════════════════════════════════════════ */

const POLL_MS = 1000;

/* ── State ──────────────────────────────────────────────────────────────── */
let status = {
    state: 'stop', elapsed: 0, duration: 0,
    volume: 100, random: 0, repeat: 0, single: 0,
    songid: -1, playlistlength: 0
};
let currentSong = {};
let activeTab   = 'queue';
let browseStack = [];
let browseUri   = '';
let isSeeking   = false;
let prevVolume  = 100;
let searchTimer = null;
let lastArtFile = null;   // track when the song changes to reload art

/* ── DOM refs ───────────────────────────────────────────────────────────── */
const playIcon  = document.getElementById('play-icon');
const seekTrack = document.getElementById('seek-track');
const seekFill  = document.getElementById('seek-fill');
const seekThumb = document.getElementById('seek-thumb');
const volSlider = document.getElementById('vol-slider');
const volValue  = document.getElementById('vol-value');
const volIcon   = document.getElementById('vol-icon');

/* ── API helpers ────────────────────────────────────────────────────────── */

async function api(action, params = {}) {
    const qs = new URLSearchParams({ action, ...params });
    const r  = await fetch(`MPDServlet?${qs}`);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
}

async function sendCommand(action, params = {}) {
    const r = await fetch('MPDServlet', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ action, ...params })
    });
    return r.json();
}

async function sendCtrl(action, params = {}) {
    await sendCommand(action, params);
    await poll();
}

/* ── Polling ────────────────────────────────────────────────────────────── */

async function poll() {
    try {
        const data = await api('nowplaying');
        hideError();

        const prevSongId   = status.songid;
        const prevQueueLen = status.playlistlength;

        status      = data.status;
        currentSong = data.song || {};

        updateNowPlaying();
        updateControls();

        if (activeTab === 'queue' &&
            (status.songid !== prevSongId || status.playlistlength !== prevQueueLen)) {
            fetchQueue();
        }
    } catch (e) {
        showError('Cannot reach MPD — ' + e.message);
    }
}

/* ── Now Playing ────────────────────────────────────────────────────────── */

function updateNowPlaying() {
    const title  = currentSong.Title  || basename(currentSong.file) || 'Nothing playing';
    const artist = currentSong.Artist || currentSong.AlbumArtist    || '—';
    const album  = currentSong.Album  || '';

    setText('np-title',  title);
    setText('np-artist', artist);
    setText('np-album',  album);

    document.title = (status.state !== 'stop' && currentSong.Title)
        ? `${title} — ${artist}`
        : 'WebPlayer — MPD';

    // Reload album art only when the file actually changes
    if (currentSong.file !== lastArtFile) {
        lastArtFile = currentSong.file;
        updateAlbumArt(currentSong.file);
    }
}

/* ── Album art ──────────────────────────────────────────────────────────── */

function updateAlbumArt(file) {
    const img         = document.getElementById('album-art');
    const placeholder = document.getElementById('art-placeholder');

    if (!file) {
        img.style.display         = 'none';
        placeholder.style.display = 'flex';
        return;
    }

    img.onload = () => {
        img.style.display         = 'block';
        placeholder.style.display = 'none';
    };
    img.onerror = () => {
        img.style.display         = 'none';
        placeholder.style.display = 'flex';
    };
    // Cache-bust is unnecessary — ArtServlet handles ETags properly
    img.src = 'ArtServlet?uri=' + encodeURIComponent(file);
}

/* ── Controls ───────────────────────────────────────────────────────────── */

function updateControls() {
    const playing = status.state === 'play';
    playIcon.className = playing ? 'fas fa-pause' : 'fas fa-play';

    if (!isSeeking) {
        const pct = status.duration > 0 ? (status.elapsed / status.duration) * 100 : 0;
        seekFill.style.width = pct + '%';
        seekThumb.style.left = pct + '%';
        setText('elapsed-time',  formatTime(status.elapsed));
        setText('duration-time', formatTime(status.duration));
    }

    volSlider.value = status.volume;
    volValue.textContent = status.volume;
    updateVolIcon(status.volume);

    toggleActive('btn-random', status.random === 1);
    toggleActive('btn-repeat', status.repeat === 1);
    toggleActive('btn-single', status.single === 1);
}

/* ── Play / Pause ───────────────────────────────────────────────────────── */

async function handlePlayPause() {
    if      (status.state === 'play')  await sendCommand('pause');
    else if (status.state === 'pause') await sendCommand('resume');
    else                               await sendCommand('play');
    await poll();
}

/* ── Queue ──────────────────────────────────────────────────────────────── */

async function fetchQueue() {
    try {
        renderQueue(await api('queue'));
    } catch (e) { console.error('fetchQueue:', e); }
}

function renderQueue(songs) {
    setText('queue-count', `Queue (${songs.length})`);
    const list = document.getElementById('queue-list');
    list.innerHTML = '';

    if (songs.length === 0) {
        list.innerHTML = '<div class="empty-msg"><i class="fas fa-list-ul"></i><br>Queue is empty</div>';
        return;
    }

    songs.forEach((song, i) => {
        const isPlaying = String(song.Id) === String(status.songid);
        const title     = song.Title  || basename(song.file) || '?';
        const artist    = song.Artist || song.AlbumArtist    || '—';
        const pos       = parseInt(song.Pos ?? i);

        const div = document.createElement('div');
        div.className = 'list-item' + (isPlaying ? ' playing' : '');
        div.innerHTML = `
            <div class="item-icon">
                ${isPlaying
            ? '<i class="fas fa-volume-high"></i>'
            : `<span class="item-num">${pos + 1}</span>`}
            </div>
            <div class="item-info">
                <span class="item-title">${esc(title)}</span>
                <span class="item-sub">${esc(artist)}</span>
            </div>
            <button class="item-remove" title="Remove from queue">
                <i class="fas fa-xmark"></i>
            </button>`;

        div.addEventListener('click', async () => {
            await sendCommand('playid', { id: parseInt(song.Id) });
            await poll();
        });
        div.querySelector('.item-remove').addEventListener('click', e => {
            e.stopPropagation();
            sendCommand('delete', { pos }).then(fetchQueue);
        });

        list.appendChild(div);
    });

    list.querySelector('.playing')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

/* ── Library browser ────────────────────────────────────────────────────── */

async function fetchBrowse(uri = '') {
    browseUri = uri;
    try { renderBrowse(await api('browse', { uri }), uri); }
    catch (e) { console.error('fetchBrowse:', e); }
}

function renderBrowse(items, uri) {
    const list      = document.getElementById('library-list');
    const pathEl    = document.getElementById('browse-path');
    const backBtn   = document.getElementById('browse-back');
    const addAllBtn = document.getElementById('browse-add-all');

    const atRoot = !uri;
    pathEl.textContent     = atRoot ? 'Library' : basename(uri);
    backBtn.style.display  = atRoot ? 'none' : 'inline-flex';
    addAllBtn.style.display =
        (!atRoot && items.some(i => i._type === 'file')) ? 'inline-flex' : 'none';

    list.innerHTML = '';

    if (items.length === 0) {
        list.innerHTML = `
            <div class="empty-msg">
                <i class="fas fa-database"></i><br>Library is empty<br>
                <button class="update-db-btn" onclick="updateDB()">
                    <i class="fas fa-arrows-rotate"></i> Update database
                </button>
            </div>`;
        return;
    }

    const dirs  = items.filter(i => i._type === 'directory');
    const plsts = items.filter(i => i._type === 'playlist');
    const files = items.filter(i => i._type === 'file');

    [...dirs, ...plsts, ...files].forEach(item => {
        const div = document.createElement('div');
        div.className = 'list-item';

        if (item._type === 'directory') {
            const name = basename(item.directory);
            div.innerHTML = `
                <div class="item-icon"><i class="fas fa-folder"></i></div>
                <div class="item-info"><span class="item-title">${esc(name)}</span></div>
                <i class="fas fa-chevron-right item-chevron"></i>`;
            div.addEventListener('click', () => {
                browseStack.push(uri);
                fetchBrowse(item.directory);
            });

        } else if (item._type === 'playlist') {
            const name = item.playlist.split('/').pop();
            div.innerHTML = `
                <div class="item-icon"><i class="fas fa-list-music"></i></div>
                <div class="item-info"><span class="item-title">${esc(name)}</span></div>
                <button class="item-add" title="Load playlist"><i class="fas fa-plus"></i></button>`;
            div.querySelector('.item-add').addEventListener('click', e => {
                e.stopPropagation();
                sendCommand('add', { uri: item.playlist }).then(() => {
                    flashQueueTab();
                    if (activeTab === 'queue') fetchQueue();
                });
            });

        } else {
            const title  = item.Title  || basename(item.file) || '?';
            const artist = item.Artist || item.AlbumArtist    || '';
            div.innerHTML = `
                <div class="item-icon"><i class="fas fa-music"></i></div>
                <div class="item-info">
                    <span class="item-title">${esc(title)}</span>
                    <span class="item-sub">${esc(artist)}</span>
                </div>
                <button class="item-add" title="Add to queue"><i class="fas fa-plus"></i></button>`;

            div.addEventListener('dblclick', async () => {
                await sendCommand('addplay', { uri: item.file });
                switchTab('queue');
                fetchQueue();
                poll();
            });
            div.querySelector('.item-add').addEventListener('click', e => {
                e.stopPropagation();
                sendCommand('add', { uri: item.file }).then(() => {
                    if (activeTab === 'queue') fetchQueue();
                    flashQueueTab();
                });
            });
        }
        list.appendChild(div);
    });
}

function browseBack() { fetchBrowse(browseStack.pop() ?? ''); }

async function addCurrentDir() {
    if (!browseUri) return;
    await sendCommand('add', { uri: browseUri });
    flashQueueTab();
    if (activeTab === 'queue') fetchQueue();
}

async function updateDB() {
    const btn = document.querySelector('.update-db-btn');
    if (btn) { btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Scanning…'; }
    await sendCommand('update');
    setTimeout(() => fetchBrowse(browseUri), 2000);
}

/* ── Search ─────────────────────────────────────────────────────────────── */

function handleSearch(q) {
    clearTimeout(searchTimer);
    if (!q.trim()) {
        document.getElementById('search-list').innerHTML =
            '<div class="empty-msg"><i class="fas fa-magnifying-glass"></i><br>Type to search</div>';
        return;
    }
    searchTimer = setTimeout(async () => {
        try { renderSearch(await api('search', { q })); }
        catch (e) { console.error('search:', e); }
    }, 400);
}

function renderSearch(songs) {
    const list = document.getElementById('search-list');
    list.innerHTML = '';
    if (songs.length === 0) {
        list.innerHTML = '<div class="empty-msg">No results</div>';
        return;
    }
    songs.forEach(song => {
        const title  = song.Title  || basename(song.file) || '?';
        const artist = song.Artist || song.AlbumArtist    || '—';
        const sub    = [artist, song.Album].filter(Boolean).join(' · ');

        const div = document.createElement('div');
        div.className = 'list-item';
        div.innerHTML = `
            <div class="item-icon"><i class="fas fa-music"></i></div>
            <div class="item-info">
                <span class="item-title">${esc(title)}</span>
                <span class="item-sub">${esc(sub)}</span>
            </div>
            <button class="item-add" title="Add to queue"><i class="fas fa-plus"></i></button>`;

        div.addEventListener('dblclick', async () => {
            await sendCommand('addplay', { uri: song.file });
            switchTab('queue');
            fetchQueue();
            poll();
        });
        div.querySelector('.item-add').addEventListener('click', e => {
            e.stopPropagation();
            sendCommand('add', { uri: song.file }).then(() => {
                if (activeTab === 'queue') fetchQueue();
                flashQueueTab();
            });
        });
        list.appendChild(div);
    });
}

function clearSearch() {
    document.getElementById('searchInput').value = '';
    handleSearch('');
}

/* ── Tab switching ──────────────────────────────────────────────────────── */

function switchTab(name) {
    activeTab = name;
    document.querySelectorAll('.tab').forEach(t =>
        t.classList.toggle('active', t.dataset.tab === name));
    document.querySelectorAll('.tab-content').forEach(c =>
        c.classList.toggle('active', c.id === `tab-${name}`));

    if (name === 'queue')   fetchQueue();
    if (name === 'library' && !document.getElementById('library-list').children.length)
        fetchBrowse('');
}

function flashQueueTab() {
    const btn = document.querySelector('[data-tab="queue"]');
    btn.classList.add('flash');
    setTimeout(() => btn.classList.remove('flash'), 400);
}

/* ── Seek bar ───────────────────────────────────────────────────────────── */

function getSeekFrac(e) {
    const r = seekTrack.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
}
function applySeekUI(frac) {
    const pct = frac * 100;
    seekFill.style.width = pct + '%';
    seekThumb.style.left = pct + '%';
    setText('elapsed-time', formatTime(frac * status.duration));
}

seekTrack.addEventListener('mousedown', e => { isSeeking = true; applySeekUI(getSeekFrac(e)); });
document.addEventListener('mousemove',  e => { if (isSeeking) applySeekUI(getSeekFrac(e)); });
document.addEventListener('mouseup', async e => {
    if (!isSeeking) return;
    isSeeking = false;
    const target = getSeekFrac(e) * status.duration;
    applySeekUI(target / status.duration);
    await sendCommand('seek', { time: target });
});

/* ── Volume ─────────────────────────────────────────────────────────────── */

function onVolumeInput(v) {
    const vol = parseInt(v);
    volValue.textContent = vol;
    updateVolIcon(vol);
    sendCommand('setvol', { volume: vol });
}

function toggleMute() {
    if (status.volume > 0) {
        prevVolume = status.volume;
        sendCommand('setvol', { volume: 0 }).then(poll);
    } else {
        sendCommand('setvol', { volume: prevVolume || 80 }).then(poll);
    }
}

function updateVolIcon(vol) {
    volIcon.className =
        vol === 0 ? 'fas fa-volume-xmark vol-icon' :
            vol < 50  ? 'fas fa-volume-low vol-icon'   :
                'fas fa-volume-high vol-icon';
}

/* ── Error banner ───────────────────────────────────────────────────────── */

function showError(msg) {
    setText('error-msg', msg);
    document.getElementById('error-banner').style.display = 'flex';
}
function hideError() {
    document.getElementById('error-banner').style.display = 'none';
}

/* ── Keyboard shortcuts ─────────────────────────────────────────────────── */

document.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT') return;
    switch (e.code) {
        case 'Space':      e.preventDefault(); handlePlayPause(); break;
        case 'ArrowRight': e.preventDefault(); sendCtrl('next'); break;
        case 'ArrowLeft':  e.preventDefault(); sendCtrl('previous'); break;
        case 'ArrowUp':    e.preventDefault();
            sendCommand('setvol', { volume: Math.min(100, status.volume + 5) }).then(poll); break;
        case 'ArrowDown':  e.preventDefault();
            sendCommand('setvol', { volume: Math.max(0, status.volume - 5) }).then(poll); break;
        case 'KeyS': sendCtrl('toggle_random'); break;
        case 'KeyR': sendCtrl('toggle_repeat'); break;
    }
});

/* ── Utilities ──────────────────────────────────────────────────────────── */

function formatTime(s) {
    s = Math.floor(s) || 0;
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
function basename(path) {
    return path ? path.split('/').pop().replace(/\.[^.]+$/, '') : '';
}
function esc(str) {
    return String(str ?? '')
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}
function toggleActive(id, on) {
    document.getElementById(id)?.classList.toggle('active', on);
}

/* ── Init ───────────────────────────────────────────────────────────────── */

async function init() {
    await poll();
    await fetchQueue();
    setInterval(poll, POLL_MS);
}

document.addEventListener('DOMContentLoaded', init);