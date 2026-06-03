/* ═══════════════════════════════════════════════════════════════════════════
   WebPlayer — MPD control frontend
   Pure control interface — no audio element, MPD plays through the system.
   ═══════════════════════════════════════════════════════════════════════════ */

const POLL_MS = 1000;

/* ── App settings (loaded from ConfigServlet) ───────────────────────────── */
let appSettings = {};
let pollIntervalId = null;

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
let lastArtFile = null;

/* ── DOM refs ───────────────────────────────────────────────────────────── */
const playIcon  = document.getElementById('play-icon');
const seekTrack = document.getElementById('seek-track');
const seekFill  = document.getElementById('seek-fill');
const seekThumb = document.getElementById('seek-thumb');
const volSlider = document.getElementById('vol-slider');
const volValue  = document.getElementById('vol-value');
const volIcon   = document.getElementById('vol-icon');
const vinylDisc = document.getElementById('vinyl-disc');
const bgArt     = document.getElementById('bg-art');

/* ── Visualizer ─────────────────────────────────────────────────────────── */

function hexToRgb(hex) {
    hex = (hex || '#7c3aed').replace('#', '');
    if (hex.length === 3) hex = hex.split('').map(c => c + c).join('');
    const n = parseInt(hex, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

const Visualizer = {
    mode:      'ellipse',
    analyser:  null,
    dataArray: null,
    animId:    null,
    simPhase:  0,
    simDecay:  0,
    vizCanvas: null,
    barCanvas: null,

    init(streamUrl, mode) {
        this.mode      = mode || 'ellipse';
        this.vizCanvas = document.getElementById('viz-canvas');
        this.barCanvas = document.getElementById('bar-canvas');
        this.resize();
        window.addEventListener('resize', () => this.resize());
        if (streamUrl && streamUrl.trim()) this.connectStream(streamUrl.trim());
        this.start();
    },

    resize() {
        const vinyl = document.getElementById('vinyl-disc');
        if (vinyl && this.vizCanvas) {
            const sz = Math.round(vinyl.offsetWidth * 1.72);
            this.vizCanvas.width  = sz;
            this.vizCanvas.height = sz;
        }
        const bar = document.querySelector('.player-bar');
        if (bar && this.barCanvas) {
            this.barCanvas.width  = bar.clientWidth;
            this.barCanvas.height = bar.clientHeight;
        }
    },

    connectStream(url) {
        try {
            const AC  = window.AudioContext || window.webkitAudioContext;
            if (!AC) return;
            const ctx = new AC();
            this.analyser = ctx.createAnalyser();
            this.analyser.fftSize = 1024;
            this.analyser.smoothingTimeConstant = 0.82;
            this.dataArray = new Uint8Array(this.analyser.frequencyBinCount);

            const el = new Audio(url);
            el.muted       = true;
            el.crossOrigin = 'anonymous';
            const src = ctx.createMediaElementSource(el);
            src.connect(this.analyser);
            // ↑ NOT connected to ctx.destination → no browser audio output
            el.play().catch(() => { this.analyser = null; });
        } catch (e) {
            this.analyser = null;
        }
    },

    /* Returns Float32Array[N] in 0..1 */
    getData(N) {
        if (this.analyser && this.dataArray) {
            this.analyser.getByteFrequencyData(this.dataArray);
            const out   = new Float32Array(N);
            const ratio = this.dataArray.length / N;
            for (let i = 0; i < N; i++)
                out[i] = this.dataArray[Math.floor(i * ratio)] / 255;
            return out;
        }
        return this.simulate(N);
    },

    simulate(N) {
        const playing = (status.state === 'play');
        this.simDecay = playing
            ? Math.min(1, this.simDecay + 0.06)
            : Math.max(0, this.simDecay - 0.025);
        if (this.simDecay === 0) return new Float32Array(N);
        this.simPhase += 0.038;
        const d = new Float32Array(N);
        for (let i = 0; i < N; i++) {
            const t    = i / N;
            const bass = Math.exp(-t * 11)  * (0.55 + 0.4  * Math.sin(this.simPhase * 2.3));
            const mid  = Math.exp(-((t - 0.22) ** 2) / 0.009) * 0.52;
            const high = Math.exp(-((t - 0.52) ** 2) / 0.028) * 0.22;
            const beat = Math.exp(-t * 7)   * Math.abs(Math.sin(this.simPhase * 3.1)) * 0.28;
            const noise= (Math.random() - 0.5) * 0.09;
            d[i] = Math.max(0, Math.min(1, (bass + mid + high + beat + noise) * this.simDecay));
        }
        return d;
    },

    start() {
        if (this.animId) cancelAnimationFrame(this.animId);
        const loop = () => { this.draw(); this.animId = requestAnimationFrame(loop); };
        loop();
    },

    stop() {
        if (this.animId) { cancelAnimationFrame(this.animId); this.animId = null; }
        this.clearAll();
    },

    draw() {
        if (this.mode === 'off') { this.clearAll(); return; }
        const [r, g, b] = hexToRgb(
            getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()
        );
        if (this.mode === 'ellipse') {
            this.drawEllipse(r, g, b);
            this.clearCanvas(this.barCanvas);
        } else {
            this.clearCanvas(this.vizCanvas);
            this.drawBar(r, g, b);
        }
    },

    drawEllipse(r, g, b) {
        const cv = this.vizCanvas;
        if (!cv) return;
        const ctx = cv.getContext('2d');
        const W = cv.width, H = cv.height, cx = W / 2, cy = H / 2;
        ctx.clearRect(0, 0, W, H);

        const N      = 220;
        const data   = this.getData(N);
        const rx     = W * 0.425;   // slightly wider than tall → ellipse feel
        const ry     = H * 0.405;
        const maxBar = Math.min(W, H) * 0.17;

        // Faint base ellipse
        ctx.beginPath();
        ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(${r},${g},${b},0.13)`;
        ctx.lineWidth = 1;
        ctx.stroke();

        ctx.shadowColor = `rgba(${r},${g},${b},0.55)`;

        for (let i = 0; i < N; i++) {
            const amp   = data[i];
            if (amp < 0.01) continue;
            const angle  = (i / N) * Math.PI * 2 - Math.PI / 2;
            const barLen = amp * maxBar;
            const bx = cx + rx * Math.cos(angle);
            const by = cy + ry * Math.sin(angle);
            const ex = bx + Math.cos(angle) * barLen;
            const ey = by + Math.sin(angle) * barLen;

            ctx.shadowBlur   = 4 + amp * 10;
            ctx.beginPath();
            ctx.moveTo(bx, by);
            ctx.lineTo(ex, ey);
            ctx.strokeStyle = `rgba(${r},${g},${b},${0.25 + amp * 0.75})`;
            ctx.lineWidth   = 1.2 + amp * 2.2;
            ctx.stroke();
        }
        ctx.shadowBlur = 0;
    },

    drawBar(r, g, b) {
        const cv = this.barCanvas;
        if (!cv) return;
        const ctx = cv.getContext('2d');
        const W = cv.width, H = cv.height;
        ctx.clearRect(0, 0, W, H);

        const N    = 90;
        const data = this.getData(N);
        const barW = W / N;

        for (let i = 0; i < N; i++) {
            const amp  = data[i];
            if (amp < 0.01) continue;
            const barH = amp * H * 0.68;
            ctx.fillStyle = `rgba(${r},${g},${b},${0.12 + amp * 0.32})`;
            ctx.fillRect(i * barW, H - barH, Math.max(1, barW - 0.8), barH);
        }
    },

    clearAll()         { this.clearCanvas(this.vizCanvas); this.clearCanvas(this.barCanvas); },
    clearCanvas(cv)    { if (cv) cv.getContext('2d').clearRect(0, 0, cv.width, cv.height); },

    setMode(mode) {
        this.mode = mode;
        // Update active state of mode buttons
        document.querySelectorAll('.viz-mode-btn').forEach(b =>
            b.classList.toggle('active', b.dataset.mode === mode));
        if (mode === 'off') this.clearAll();
    }
};

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
        updateVinyl();

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

    if (currentSong.file !== lastArtFile) {
        lastArtFile = currentSong.file;
        updateArt(currentSong.file);
    }
}

/* ── Art: vinyl + blurred bg ────────────────────────────────────────────── */

function updateArt(file) {
    const img         = document.getElementById('vinyl-img');
    const placeholder = document.getElementById('vinyl-placeholder');
    const hole        = document.getElementById('vinyl-hole');

    if (!file) {
        img.style.display         = 'none';
        placeholder.style.display = 'flex';
        if (hole) hole.style.display = 'none';
        bgArt.style.backgroundImage = '';
        bgArt.classList.remove('visible');
        return;
    }

    const artUrl = 'ArtServlet?uri=' + encodeURIComponent(file);

    // Vinyl centre art
    img.onload = () => {
        img.style.display         = 'block';
        placeholder.style.display = 'none';
        if (hole) hole.style.display = 'block'; // show hole only when art is visible
    };
    img.onerror = () => {
        img.style.display         = 'none';
        placeholder.style.display = 'flex';
        if (hole) hole.style.display = 'none';  // keep hole hidden over placeholder
    };
    img.src = artUrl;

    // Full-bleed background — probe first; only show when art exists
    const probe = new Image();
    probe.onload = () => {
        bgArt.style.backgroundImage = `url('${artUrl}')`;
        bgArt.classList.add('visible');
    };
    probe.onerror = () => {
        bgArt.style.backgroundImage = '';
        bgArt.classList.remove('visible');
    };
    probe.src = artUrl;
}

/* ── Vinyl spin state ───────────────────────────────────────────────────── */

function updateVinyl() {
    vinylDisc.classList.toggle('playing', status.state === 'play');
}

/* ── Controls ───────────────────────────────────────────────────────────── */

function updateControls() {
    playIcon.className = status.state === 'play' ? 'fas fa-pause' : 'fas fa-play';

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
    try { renderQueue(await api('queue')); }
    catch (e) { console.error('fetchQueue:', e); }
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

        // Thumbnail: try ArtServlet, fall back to number
        const thumbHtml = song.file
            ? `<div class="item-thumb">
                   <img src="ArtServlet?uri=${encodeURIComponent(song.file)}" alt=""
                        onerror="this.style.display='none';
                                 this.nextElementSibling.style.display='flex'">
                   <div class="thumb-fallback" style="display:none">
                       <span class="item-num">${pos + 1}</span>
                   </div>
                   <div class="playing-overlay">
                       <i class="fas fa-volume-high"></i>
                   </div>
               </div>`
            : `<div class="item-thumb">
                   <div class="thumb-fallback">
                       <span class="item-num">${pos + 1}</span>
                   </div>
                   ${isPlaying ? '<div class="playing-overlay"><i class="fas fa-volume-high"></i></div>' : ''}
               </div>`;

        div.innerHTML = `
            ${thumbHtml}
            <div class="item-info">
                <span class="item-title">${esc(title)}</span>
                <span class="item-sub">${esc(artist)}</span>
            </div>
            <button class="item-remove" data-tip="Remove">
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
                <div class="item-thumb">
                    <i class="fas fa-folder"></i>
                </div>
                <div class="item-info"><span class="item-title">${esc(name)}</span></div>
                <i class="fas fa-chevron-right item-chevron"></i>`;
            div.addEventListener('click', () => { browseStack.push(uri); fetchBrowse(item.directory); });

        } else if (item._type === 'playlist') {
            const name = item.playlist.split('/').pop();
            div.innerHTML = `
                <div class="item-thumb"><i class="fas fa-list-music"></i></div>
                <div class="item-info"><span class="item-title">${esc(name)}</span></div>
                <button class="item-add" data-tip="Add to queue"><i class="fas fa-plus"></i></button>`;
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
                <div class="item-thumb">
                    <img src="ArtServlet?uri=${encodeURIComponent(item.file)}" alt=""
                         onerror="this.style.display='none';
                                  this.nextElementSibling.style.display='flex'">
                    <div class="thumb-fallback" style="display:none">
                        <i class="fas fa-music"></i>
                    </div>
                </div>
                <div class="item-info">
                    <span class="item-title">${esc(title)}</span>
                    <span class="item-sub">${esc(artist)}</span>
                </div>
                <button class="item-add" data-tip="Add to queue"><i class="fas fa-plus"></i></button>`;

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
            <div class="item-thumb">
                <img src="ArtServlet?uri=${encodeURIComponent(song.file)}" alt=""
                     onerror="this.style.display='none';
                              this.nextElementSibling.style.display='flex'">
                <div class="thumb-fallback" style="display:none">
                    <i class="fas fa-music"></i>
                </div>
            </div>
            <div class="item-info">
                <span class="item-title">${esc(title)}</span>
                <span class="item-sub">${esc(sub)}</span>
            </div>
            <button class="item-add" data-tip="Add to queue"><i class="fas fa-plus"></i></button>`;

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
    if (status.volume > 0) { prevVolume = status.volume; sendCommand('setvol', { volume: 0 }).then(poll); }
    else sendCommand('setvol', { volume: prevVolume || 80 }).then(poll);
}
function updateVolIcon(vol) {
    volIcon.className =
        vol === 0 ? 'fas fa-volume-xmark vol-icon' :
            vol < 50  ? 'fas fa-volume-low vol-icon'   :
                'fas fa-volume-high vol-icon';
}

/* ── Error banner ───────────────────────────────────────────────────────── */

function showError(msg) { setText('error-msg', msg); document.getElementById('error-banner').style.display = 'flex'; }
function hideError()    { document.getElementById('error-banner').style.display = 'none'; }

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
        .replace(/&/g,'&amp;').replace(/</g,'&lt;')
        .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function setText(id, text) { const el = document.getElementById(id); if (el) el.textContent = text; }
function toggleActive(id, on) { document.getElementById(id)?.classList.toggle('active', on); }

/* ── Settings panel ─────────────────────────────────────────────────────── */

function openSettings() {
    document.getElementById('settings-panel').classList.add('open');
    document.getElementById('settings-backdrop').classList.add('open');
}
function closeSettings() {
    document.getElementById('settings-panel').classList.remove('open');
    document.getElementById('settings-backdrop').classList.remove('open');
}

async function loadSettings() {
    try {
        appSettings = await (await fetch('ConfigServlet')).json();
        document.getElementById('s-mpd-host').value  = appSettings['mpd.host']       || 'localhost';
        document.getElementById('s-mpd-port').value  = appSettings['mpd.port']       || '6600';
        document.getElementById('s-music-dir').value = appSettings['music.dir']      || '~/Music';
        document.getElementById('s-accent').value    = appSettings['ui.accentColor'] || '#7c3aed';
        document.getElementById('s-stream-url').value = appSettings['stream.url']    || '';

        const speed   = parseInt(appSettings['ui.vinylSpeed']  || '6');
        const opacity = Math.round(parseFloat(appSettings['ui.bgOpacity'] || '0.18') * 100);
        document.getElementById('s-vinyl-speed').value        = speed;
        document.getElementById('s-vinyl-speed-val').textContent = speed + ' s/rev';
        document.getElementById('s-bg-opacity').value         = opacity;
        document.getElementById('s-bg-opacity-val').textContent  = opacity + ' %';

        const togPause = document.getElementById('tog-pause-close');
        if (togPause) togPause.classList.toggle('on', appSettings['player.pauseOnClose'] === 'true');

        // Apply appearance immediately
        applyAccent(appSettings['ui.accentColor'] || '#7c3aed');
        applyVinylSpeed(speed);
        applyBgOpacity(parseFloat(appSettings['ui.bgOpacity'] || '0.18'));

        // Sync viz mode buttons
        const savedMode = appSettings['visualizer.mode'] || 'ellipse';
        document.querySelectorAll('.viz-mode-btn').forEach(b =>
            b.classList.toggle('active', b.dataset.mode === savedMode));
    } catch (e) { console.warn('Could not load settings:', e); }
}

function setVizMode(mode) {
    Visualizer.setMode(mode);
    saveSetting('visualizer.mode', mode);
}

async function saveStreamUrl(url) {
    await saveSetting('stream.url', url);
    // Reconnect visualizer with the new URL
    if (url.trim()) Visualizer.connectStream(url.trim());
}

async function saveSetting(key, value) {
    appSettings[key] = String(value);
    await fetch('ConfigServlet', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ [key]: String(value) })
    });
}

async function toggleSetting(key, btn) {
    const newVal = appSettings[key] !== 'true';
    btn.classList.toggle('on', newVal);
    appSettings[key] = String(newVal);
    await saveSetting(key, newVal);
}

async function saveConn() {
    const host = document.getElementById('s-mpd-host').value.trim();
    const port = document.getElementById('s-mpd-port').value.trim();
    const dir  = document.getElementById('s-music-dir').value.trim();
    await fetch('ConfigServlet', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 'mpd.host': host, 'mpd.port': port, 'music.dir': dir })
    });
    Object.assign(appSettings, { 'mpd.host': host, 'mpd.port': port, 'music.dir': dir });
    await testConn();
}

async function testConn() {
    const statusEl = document.getElementById('conn-status');
    const host = document.getElementById('s-mpd-host').value.trim();
    const port = document.getElementById('s-mpd-port').value.trim();
    statusEl.className = 'conn-status';
    statusEl.textContent = 'Testing…';
    try {
        const r = await (await fetch(`ConfigServlet?action=test&host=${encodeURIComponent(host)}&port=${encodeURIComponent(port)}`)).json();
        if (r.ok) {
            statusEl.className = 'conn-status ok';
            statusEl.textContent = `✓ Connected — MPD state: ${r.state}`;
        } else {
            statusEl.className = 'conn-status err';
            statusEl.textContent = '✗ ' + (r.error || 'Connection failed');
        }
    } catch (e) {
        statusEl.className = 'conn-status err';
        statusEl.textContent = '✗ ' + e.message;
    }
}

/* Live preview helpers */
function previewAccent(hex) {
    applyAccent(hex);
    saveSetting('ui.accentColor', hex);
}
function previewVinylSpeed(val) {
    document.getElementById('s-vinyl-speed-val').textContent = val + ' s/rev';
    applyVinylSpeed(parseInt(val));
    // save is handled by onchange
}
function previewBgOpacity(val) {
    document.getElementById('s-bg-opacity-val').textContent = val + ' %';
    applyBgOpacity(val / 100);
    // save handled by onchange
}

function applyAccent(hex) {
    const r = parseInt(hex.slice(1,3),16), g = parseInt(hex.slice(3,5),16), b = parseInt(hex.slice(5,7),16);
    const root = document.documentElement;
    root.style.setProperty('--accent',      hex);
    root.style.setProperty('--accent-dim',  `rgb(${Math.max(0,r-30)},${Math.max(0,g-30)},${Math.max(0,b-30)})`);
    root.style.setProperty('--accent-glow', `rgba(${r},${g},${b},.35)`);
}
function applyVinylSpeed(secs) {
    document.documentElement.style.setProperty('--vinyl-speed', secs + 's');
}
function applyBgOpacity(opacity) {
    document.documentElement.style.setProperty('--bg-opacity', opacity);
}

/* ── Init ───────────────────────────────────────────────────────────────── */

async function init() {
    await loadSettings();
    await poll();
    await fetchQueue();

    // Start visualizer with saved mode and optional stream URL
    Visualizer.init(
        appSettings['stream.url']      || '',
        appSettings['visualizer.mode'] || 'ellipse'
    );

    const ms = parseInt(appSettings['player.pollInterval'] || POLL_MS);
    pollIntervalId = setInterval(poll, ms);

    window.addEventListener('pagehide', () => {
        if (appSettings['player.pauseOnClose'] === 'true' && status.state === 'play') {
            navigator.sendBeacon('MPDServlet', new Blob(
                [JSON.stringify({ action: 'pause' })],
                { type: 'application/json' }
            ));
        }
    });
}

document.addEventListener('DOMContentLoaded', init);