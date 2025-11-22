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

// Initial Load
document.addEventListener('DOMContentLoaded', () => {
    fetchAndRender('PlaylistServlet');
});

let searchTimeout;

// Debounce function to prevent spamming the server
function handleSearch() {
    clearTimeout(searchTimeout);
    const query = document.getElementById('searchInput').value.trim();

    searchTimeout = setTimeout(() => {
        if (query.length === 0) {
            // If empty, load all songs
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
            container.innerHTML = '';

            if(songs.length === 0) {
                container.innerHTML = '<div style="padding:20px; color:#666; text-align:center;">No songs found.</div>';
                return;
            }

            songs.forEach(song => {
                // Create visual elements
                const div = document.createElement('div');
                div.className = 'song-item';

                // Generate Thumbnail URL from YouTube ID
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
    document.getElementById('addModal').style.display = 'block';
    document.getElementById('inputUrl').focus();

    document.getElementById('loadingBar').style.display = 'none';
    const statusText = document.getElementById('loadingStatus');
    if (statusText) statusText.innerText = '';

    // Reset bar
    const bar = document.querySelector('.loader-bar');
    if (bar) {
        bar.classList.remove('determinate');
        bar.style.width = '50%';
    }
}

function closeModal() {
    document.getElementById('addModal').style.display = 'none';
}

// Close modal if clicking outside the box
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
    const url = document.getElementById('inputUrl').value;
    const btn = document.getElementById('submitBtn');
    const loaderContainer = document.getElementById('loadingBar');
    const loaderBar = document.querySelector('.loader-bar');
    const listMatch = url.match(/[?&]list=([^#\&\?]+)/);
    const playlistId = listMatch ? listMatch[1] : null;
    const videoId = !playlistId ? extractVideoId(url) : null;

    if (!videoId && !playlistId) {
        alert("Invalid URL");
        return;
    }

    const originalText = btn.innerText;
    btn.innerText = "Processing...";
    btn.disabled = true;

    loaderContainer.style.display = 'block';
    loaderBar.classList.remove('determinate');
    loaderBar.style.width = '50%';

    try {
        const response = await fetch('AddServlet', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                videoId: videoId,
                playlistId: playlistId
            })
        });

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");

        loaderBar.classList.add('determinate');
        loaderBar.style.width = '0%';
        if(statusText) statusText.style.display = 'block';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            const chunk = decoder.decode(value, { stream: true });
            const lines = chunk.split("\n");

            for (const line of lines) {
                if (!line.trim()) continue;

                // Expected format from Java: "PROGRESS:5/20" or "DONE:Imported 50 songs"
                if (line.startsWith("PROGRESS:")) {
                    const parts = line.split(":")[1].split("/");
                    const current = parseInt(parts[0]);
                    const total = parseInt(parts[1]);

                    const percent = (current / total) * 100;
                    loaderBar.style.width = percent + "%";

                    if(statusText) statusText.innerText = `Importing ${current} of ${total}...`;
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
        document.getElementById('inputUrl').value = '';
        fetchAndRender('PlaylistServlet');

    } catch (err) {
        alert("Error: " + err.message);
    } finally {
        btn.innerText = originalText;
        btn.disabled = false;
        loaderContainer.style.display = 'none';
        if(statusText) statusText.style.display = 'none';
    }
}