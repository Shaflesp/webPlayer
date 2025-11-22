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

function submitSong() {
    const url = document.getElementById('inputUrl').value;
    const btn = document.getElementById('submitBtn');

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

    fetch('AddServlet', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            videoId: videoId,
            playlistId: playlistId,
            title: null,
            artist: null
        })
    })
    .then(response => response.text())
    .then(data => {
        alert(data);
        closeModal();
        document.getElementById('inputUrl').value = '';
        fetchAndRender('PlaylistServlet'); // Refresh list without page reload
        btn.innerText = originalText;
        btn.disabled = false;
    })
    .catch(err => {
        alert("Error: " + err);
        btn.innerText = originalText;
        btn.disabled = false;
    });
}