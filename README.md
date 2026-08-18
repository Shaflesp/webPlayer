# WebPlayer

A self-hosted web frontend for [MPD](https://www.musicpd.org/) (Music Player Daemon) — control your local music library from any browser on your network, with a real-time audio visualizer, YouTube playlist import, and remote listening support.

[![Maven Build](https://github.com/Shaflesp/webPlayer/actions/workflows/maven.yml/badge.svg)](https://github.com/Shaflesp/webPlayer/actions/workflows/maven.yml)
[![Qodana](https://github.com/Shaflesp/webPlayer/actions/workflows/qodana_code_quality.yml/badge.svg)](https://github.com/Shaflesp/webPlayer/actions/workflows/qodana_code_quality.yml)

---

## What is this?

WebPlayer sits in front of an existing MPD installation and gives it a modern web UI. MPD keeps doing what it's good at (gapless local playback, decoding, library management); WebPlayer adds:

- **A real-time FFT visualizer** — reads MPD's own audio output directly via a FIFO pipe (the same source ncmpcpp uses), runs an actual Cooley-Tukey FFT, and renders it as a smooth animated ring or bar spectrum around the now-playing view. Not a simulation — it responds to the actual audio.
- **YouTube playlist sync** — paste a playlist URL, and it downloads via `yt-dlp` straight into your MPD library, with automatic browser cookie detection (Zen, Firefox, Waterfox, Chrome, Chromium, Brave, Edge, Vivaldi, Opera — including Flatpak/Snap variants), automatic retry-limiting for permanently broken videos, and automatic cleanup of any leftover artifacts from interrupted downloads.
- **Remote listening** — any device on your network can open WebPlayer in a browser and hear audio through its own speakers, with an automatic latency-calibration feature that keeps a remote listener in sync with the local speakers via a PipeWire delay sink.
- **MPD-native playlists** — save, load, and browse MPD's own playlist store, separate from the YouTube-sync library folders.
- **Drag-to-reorder queue**, album art (filesystem covers or MPD's embedded-art protocol), library browsing, and search.

It's built to be genuinely easy to install: `./install.sh` detects your distro, installs the two unavoidable system dependencies (MPD and ffmpeg — everything else is bundled), configures MPD correctly, and sets up autostart. No Docker, no manual `mpd.conf` archaeology.

## Screenshots

*WIP*

---

## Quick start (for end users)

If you just want to run WebPlayer and not build it yourself, grab the latest release tarball from the [Releases](https://github.com/Shaflesp/webPlayer/releases) page (or build one yourself — see below), then:

```bash
tar xzf webplayer-1.0.0-linux-x64.tar.gz
cd webplayer-1.0.0-linux-x64
./install.sh
```

The installer will:
1. Detect your package manager (pacman / apt / dnf / zypper) and install MPD, ffmpeg, and `python-mutagen` if any are missing
2. Write a working `~/.config/mpd/mpd.conf` (backing up any existing one) with the FIFO output the visualizer needs and the HTTP stream output remote listening needs
3. Enable MPD as a systemd user service
4. Install WebPlayer to `~/.local/share/webplayer/` and set it up to autostart on login
5. Open `http://localhost:8080` in your browser once everything's confirmed running

To remove everything the installer set up (except MPD itself, in case you use it for other things):

```bash
./install.sh --uninstall
```

Currently **Linux-only** — see [Limitations](#known-limitations) below.

---

## Building from source

### Requirements

- Java 21+
- Maven
- Node.js + npm
- `curl` (used to fetch the bundled `yt-dlp` binary at build time)

### Build

```bash
git clone https://github.com/Shaflesp/webPlayer.git
cd webPlayer
./build.sh              # fat JAR only        → target/webplayer-1.0.0.jar
./build.sh --app        # + jpackage app image → target/dist/WebPlayer/  (bundles its own JRE, no Java needed to run it)
./build.sh --release    # + release tarball    → target/release/webplayer-1.0.0-linux-x64.tar.gz  (the installable bundle described above)
```

The fat JAR already contains the built React frontend and a bundled `yt-dlp` binary (downloaded once and cached on subsequent builds).

### Running without installing

```bash
java -jar target/webplayer-1.0.0.jar
```

You'll still need MPD and ffmpeg installed and MPD configured with a FIFO output (see `install.sh`'s generated `mpd.conf` for the exact block, or just run the installer — it's safe to run against a dev build too).

### Frontend development

```bash
cd src/frontend
npm install
npm run dev       # Vite dev server on :5173, proxies API calls to a running backend on :8080
npm run build     # production build → src/frontend/dist/
```

---

## Configuration

Settings are stored in `~/.config/webplayer/config.properties` and are editable from the in-app Settings panel — connection details, appearance, the visualizer's FIFO path, YouTube sync's browser cookie override (for setups auto-detection doesn't cover), and the remote-listening stream URL are all there. `mpd.conf` itself is only touched by the installer and by the sync-delay calibration feature (which edits the `pipewire` output's `sink` line and restarts MPD when you calibrate).

---

## Testing

```bash
mvn test                              # backend
cd src/frontend && npm test           # frontend
```

**Backend** (`src/test/java/`) covers the MPD protocol client against a fake in-process server, settings persistence, the YouTube-sync job lifecycle (including the wait/notify signalling between the sync worker and its SSE subscriber), and the FFT/DSP pipeline against synthetic audio signals.

**Frontend** (`src/frontend/src/test/`) covers the visualizer's signal-processing math (resampling, spectrum mirroring, treble boost, temporal smoothing) and component behavior for the sync panel, settings hydration, the remote-listening/calibration flow, and queue drag-reorder.

---

## Architecture

```
src/
├── main/java/MPD/
│   ├── MPDClient.java          MPD protocol client (plain TCP socket)
│   ├── config/                 Settings persistence, Spring/Gson wiring
│   ├── service/                Business logic: MPD bridging, YouTube sync,
│   │                           FIFO→FFT pipeline, PipeWire sync-delay
│   └── controller/              REST + SSE endpoints
├── frontend/src/
│   ├── components/              React UI
│   ├── hooks/                   Polling, visualizer rendering
│   ├── store.ts                 Zustand global state
│   └── api.ts                   Backend API client
└── test/                        Backend tests
    frontend/src/test/           Frontend tests
```

Backend: Spring Boot 3.3 on Java 21 (virtual threads for SSE streaming), embedded Tomcat, Gson for JSON. Frontend: React 18 + TypeScript + Vite + Zustand. Communication is REST for commands and Server-Sent Events for the visualizer stream and YouTube-sync progress log.

---

## Known limitations

- **Linux only.** MPD's Windows/macOS builds and audio backends aren't currently supported by the packaging or install script.
- **Remote listening has a few seconds of latency** — it's an HTTP audio stream, not a low-latency protocol, so a remote listener hears audio a few seconds behind the local speakers. The sync-delay calibration feature (PipeWire filter-chain) can delay the local speakers to match, keeping both listening points *in sync with each other*, but neither is instant relative to the actual play command.
- **Sync-delay calibration requires PipeWire.** It writes a PipeWire `filter-chain` config and restarts both PipeWire and MPD — no effect (and no harm) on a system using a different audio server.
- YouTube sync depends on `yt-dlp`, which is in an active, ongoing arms race with YouTube's anti-bot measures. The app self-updates the bundled binary on request (Settings → Sync panel), which resolves the large majority of sync failures.

---

## License

All rights reserved.