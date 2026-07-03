#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# WebPlayer build script
#
# Usage:
#   ./build.sh              → fat JAR only        (target/webplayer-0.1.0.jar)
#   ./build.sh --app        → + jpackage app image (target/dist/WebPlayer/)
#   ./build.sh --release    → + release tarball with installer
#                              (target/release/webplayer-0.1.0-linux-x64.tar.gz)
#
# The fat JAR already contains:
#   • The React frontend (built from src/frontend/)
#   • A bundled yt-dlp binary (downloaded once from GitHub on first build)
#
# Build requirements:  curl, node, npm, mvn, java 21+   (+ jpackage for --app/--release)
# Runtime requirements (end user): mpd, ffmpeg          (handled by install.sh)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")"

MODE="jar"
case "${1:-}" in
    --app)     MODE="app" ;;
    --release) MODE="release" ;;
esac

echo "╔══════════════════════════════════════════╗"
echo "║        WebPlayer — build starting        ║"
echo "╚══════════════════════════════════════════╝"

# ── 1. Preflight ──────────────────────────────────────────────────────────────

need() { command -v "$1" &>/dev/null || { echo "✗ '$1' not found. Install it first."; exit 1; }; }
need curl; need node; need npm; need mvn; need java
if [[ "$MODE" != "jar" ]]; then need jpackage; fi

# ── 2. Frontend ───────────────────────────────────────────────────────────────

echo ""
echo "▶ Building React frontend…"
( cd src/frontend && npm ci && npm run build )
echo "  ✓ Frontend built → src/frontend/dist/"

# ── 3. yt-dlp binary (cached after first run) ────────────────────────────────

YTDLP_DEST="src/main/resources/native/yt-dlp"
mkdir -p "$(dirname "$YTDLP_DEST")"

if [[ -f "$YTDLP_DEST" ]]; then
    echo ""
    echo "▶ yt-dlp binary already cached, skipping download."
else
    echo ""
    echo "▶ Downloading yt-dlp binary…"
    curl -L --progress-bar -o "$YTDLP_DEST" \
         "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    chmod +x "$YTDLP_DEST"
    echo "  ✓ yt-dlp downloaded → $YTDLP_DEST"
fi

# ── 4. Maven ──────────────────────────────────────────────────────────────────

echo ""
echo "▶ Building fat JAR…"
mvn package -DskipTests -q
JAR_PATH="target/webplayer-0.1.0.jar"
echo "  ✓ JAR built → $JAR_PATH"

if [[ "$MODE" == "jar" ]]; then
    echo ""
    echo "╔══════════════════════════════════════════╗"
    echo "║              Build complete!             ║"
    echo "╠══════════════════════════════════════════╣"
    echo "║  JAR:      $JAR_PATH"
    echo "║  Run with: java -jar $JAR_PATH"
    echo "╠══════════════════════════════════════════╣"
    echo "║  For a ready-to-use installer, run:      ║"
    echo "║    ./build.sh --release                  ║"
    echo "╚══════════════════════════════════════════╝"
    exit 0
fi

# ── 5. jpackage app image ────────────────────────────────────────────────────

echo ""
echo "▶ Running jpackage (bundling JRE)…"
mvn package -Pjpackage -DskipTests -q
APP_IMAGE="target/dist/WebPlayer"
echo "  ✓ App image built → $APP_IMAGE/"

if [[ "$MODE" == "app" ]]; then
    echo ""
    echo "╔══════════════════════════════════════════╗"
    echo "║              Build complete!             ║"
    echo "╠══════════════════════════════════════════╣"
    echo "║  App image: $APP_IMAGE/"
    echo "║  Run with:  $APP_IMAGE/bin/WebPlayer"
    echo "║  (no Java install needed — JRE bundled)  ║"
    echo "╚══════════════════════════════════════════╝"
    exit 0
fi

# ── 6. Release tarball ────────────────────────────────────────────────────────

echo ""
echo "▶ Assembling release bundle…"

RELEASE_NAME="webplayer-0.1.0-linux-x64"
RELEASE_DIR="target/release/$RELEASE_NAME"

rm -rf "target/release"
mkdir -p "$RELEASE_DIR"

cp -r "$APP_IMAGE" "$RELEASE_DIR/WebPlayer"
cp install.sh      "$RELEASE_DIR/install.sh"
chmod +x            "$RELEASE_DIR/install.sh"

cat > "$RELEASE_DIR/README.txt" << 'README'
WebPlayer — MPD Web Frontend
═════════════════════════════

QUICK START
-----------
  ./install.sh

That's it. The installer will:
  • Detect your distro (Arch/Debian/Fedora/openSUSE) and install
    mpd + ffmpeg if missing
  • Write a working ~/.config/mpd/mpd.conf (backs up any existing one)
  • Enable MPD as a systemd user service
  • Install WebPlayer to ~/.local/share/webplayer/
  • Set up auto-start on login
  • Open http://localhost:8080 in your browser

No Java required — the app image includes its own runtime.

MANAGING WEBPLAYER
-------------------
  Launch (if not auto-started):  webplayer
  View logs:                     journalctl --user -u webplayer -f
  Stop:                          systemctl --user stop webplayer
  Restart:                       systemctl --user restart webplayer
  Uninstall:                     ./install.sh --uninstall

ADDING MUSIC
------------
  • Drop files into ~/Music, or
  • Use the in-app YouTube playlist sync (cloud icon in the sidebar)

The MPD database updates automatically after each sync.
README

# Build the tarball
( cd target/release && tar -czf "${RELEASE_NAME}.tar.gz" "$RELEASE_NAME" )

SIZE=$(du -h "target/release/${RELEASE_NAME}.tar.gz" | cut -f1)

echo "  ✓ Release bundle created"
echo ""
echo "╔══════════════════════════════════════════╗"
echo "║              Build complete!             ║"
echo "╠══════════════════════════════════════════╣"
echo "║  Release:  target/release/${RELEASE_NAME}.tar.gz"
echo "║  Size:     $SIZE"
echo "╠══════════════════════════════════════════╣"
echo "║  To distribute, share the .tar.gz file.  ║"
echo "║  End users just run:                     ║"
echo "║    tar xzf ${RELEASE_NAME}.tar.gz"
echo "║    cd ${RELEASE_NAME}"
echo "║    ./install.sh                          ║"
echo "╚══════════════════════════════════════════╝"