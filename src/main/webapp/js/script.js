/* -------------------------------------------------------------------------- */
/* YouTube API                                  */
/* -------------------------------------------------------------------------- */
var tag = document.createElement('script');
tag.src = "https://www.youtube.com/iframe_api";
var firstScriptTag = document.getElementsByTagName('script')[0];
firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

var player;

function onYouTubeIframeAPIReady() {
    player = new YT.Player('yt-player', {
        height: '100%',
        width: '100%',
        playerVars: { 'playsinline': 1 },
        events: { 'onReady': onPlayerReady }
    });
}

function onPlayerReady(event) {
    console.log("Player Ready");
}

/* -------------------------------------------------------------------------- */
/* Playlist & Search Logic                          */
/* -------------------------------------------------------------------------- */

document.addEventListener('DOMContentLoaded', () => {
    fetchAndRender('PlaylistServlet');
});

let searchTimeout;

function handleSearch() {
    clearTimeout(searchTimeout);
    const input = document.getElementById('searchInput');
    if (!input) return;

    const query = input.value.trim();

    searchTimeout = setTimeout(() => {
        if (query.length === 0) {
            fetchAndRender('PlaylistServlet');
        } else {
            fetchAndRender(`SearchServlet?q=${encodeURIComponent(query)}`);
        }
    }, 400);
}

function fetchAndRender(url) {
    fetch(url)
        .then(response => response.json())
        .then(songs => {
            const container = document.getElementById('playlist-ui');
            if (!container) return;

            container.innerHTML = '';

            if(songs.length === 0) {
                container.innerHTML = '<div style="padding:20px; color:#666; text-align:center;">No songs found.</div>';
                return;
            }

            songs.forEach(song => {
                const div = document.createElement('div');
                div.className = 'song-item';

                const thumbUrl = `https://img.youtube.com/vi/${song.videoId}/default.jpg`;

                div.innerHTML = `
                    <img src="${thumbUrl}" class="song-thumb" alt="album art">
                    <div class="song-info">
                        <span class="song-title">${song.title}</span>
                        <span class="song-artist">${song.artist}</span>
                    </div>
                    <i class="fas fa-play" style="margin-left:auto; color: #444; font-size: 12px;"></i>
                `;

                div.onclick = () => {
                    if(player && player.loadVideoById) {
                        player.loadVideoById(song.videoId);
                    }
                };

                container.appendChild(div);
            });
        })
        .catch(err => console.error("Error fetching songs:", err));
}

/* -------------------------------------------------------------------------- */
/* Modal & Add Logic                             */
/* -------------------------------------------------------------------------- */

function openModal() {
    const modal = document.getElementById('addModal');
    if (modal) {
        modal.style.display = 'block';
        const input = document.getElementById('inputUrl');
        if (input) input.focus();
    }

    // Reset load bar
    const loaderBar = document.querySelector('.loader-bar');
    const loadingBar = document.getElementById('loadingBar');
    const statusText = document.getElementById('loadingStatus');

    if (loadingBar) loadingBar.style.display = 'none';
    if (statusText) statusText.innerText = '';

    if (loaderBar) {
        loaderBar.classList.remove('determinate');
        loaderBar.style.width = '50%';
    }
}

function closeModal() {
    const modal = document.getElementById('addModal');
    if (modal) modal.style.display = 'none';
}

window.onclick = function(event) {
    const modal = document.getElementById('addModal');
    if (event.target == modal) {
        modal.style.display = "none";
    }
}

function extractVideoId(url) {
    var regExp = /^.*(youtu.be\/|v\/|u\/\w\/|embed\/|watch\?v=|\&v=)([^#\&\?]*).*/;
    var match = url.match(regExp);
    return (match && match[2].length == 11) ? match[2] : null;
}

async function submitSong() {
    const btn = document.getElementById('submitBtn');
    const inputElement = document.getElementById('inputUrl');
    const loaderContainer = document.getElementById('loadingBar');
    const loaderBar = document.querySelector('.loader-bar');
    const statusText = document.getElementById('loadingStatus');

    const originalText = btn ? btn.innerText : "Import";

    if (!inputElement) {
        alert("Error: Input field missing.");
        return;
    }
    const url = inputElement.value;

    const listMatch = url.match(/[?&]list=([^#\&\?]+)/);
    const playlistId = listMatch ? listMatch[1] : null;
    const videoId = !playlistId ? extractVideoId(url) : null;

    if (!videoId && !playlistId) {
        alert("Invalid URL");
        return;
    }

    // 3. UI Updates
    if (btn) {
        btn.innerText = "Processing...";
        btn.disabled = true;
    }

    if (loaderContainer) loaderContainer.style.display = 'block';
    if (loaderBar) {
        loaderBar.classList.remove('determinate');
        loaderBar.style.width = '50%';
    }

    // 4. Network Request
    try {
        const response = await fetch('AddServlet', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                videoId: videoId,
                playlistId: playlistId
            })
        });

        if (!response.body) throw new Error("ReadableStream not supported.");

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");

        if (loaderBar) {
            loaderBar.classList.add('determinate');
            loaderBar.style.width = '0%';
        }
        if (statusText) statusText.style.display = 'block';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            const chunk = decoder.decode(value, { stream: true });
            const lines = chunk.split("\n");

            for (const line of lines) {
                if (!line.trim()) continue;

                if (line.startsWith("PROGRESS:")) {
                    const parts = line.split(":")[1].split("/");
                    const current = parseInt(parts[0]);
                    const total = parseInt(parts[1]);

                    const percent = (current / total) * 100;
                    if (loaderBar) loaderBar.style.width = percent + "%";
                    if (statusText) statusText.innerText = `Importing ${current} of ${total}...`;
                }
                else if (line.startsWith("DONE:")) {
                    alert(line.split(":")[1]);
                }
                else if (line.startsWith("ERROR:")) {
                    throw new Error(line.split(":")[1]);
                }
            }
        }

        closeModal();
        if (inputElement) inputElement.value = '';
        fetchAndRender('PlaylistServlet');

    } catch (err) {
        console.error("Catch Block Error:", err);
        alert("Error: " + err.message);
    } finally {
        if (btn) {
            btn.innerText = originalText;
            btn.disabled = false;
        }

        if (loaderContainer) loaderContainer.style.display = 'none';
        if (statusText) statusText.style.display = 'none';
    }
}