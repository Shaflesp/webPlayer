#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# WebPlayer installer
#
# Usage (from project root after building, or inside the release tarball):
#   ./install.sh              # auto-detects app image or JAR
#   ./install.sh --uninstall  # removes everything installed by this script
#
# What it does:
#   1. Detects distro + package manager
#   2. Installs mpd + ffmpeg
#   3. Writes ~/.config/mpd/mpd.conf  (skips if one already exists)
#   4. Enables + starts the MPD user service
#   5. Installs WebPlayer to ~/.local/share/webplayer/
#   6. Creates a systemd user service (auto-start on login)
#   7. Creates ~/.local/bin/webplayer launcher + .desktop entry
#   8. Health-checks everything and opens the browser
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Colour palette ────────────────────────────────────────────────────────────
if [[ -t 1 ]]; then  # only colour when outputting to a terminal
    BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'
    CYAN='\033[0;36m'; WHITE='\033[1;37m'; BLUE='\033[0;34m'
else
    BOLD=''; DIM=''; NC=''; RED=''; GREEN=''; YELLOW=''; CYAN=''; WHITE=''; BLUE=''
fi

VERSION="1.0.0"
TOTAL_STEPS=7

INSTALL_DIR="$HOME/.local/share/webplayer"
BIN_DIR="$HOME/.local/bin"
APPS_DIR="$HOME/.local/share/applications"
SERVICES_DIR="$HOME/.config/systemd/user"
MPD_DIR="$HOME/.config/mpd"

# ── Helpers ───────────────────────────────────────────────────────────────────

step()  { echo -e "\n${BOLD}${BLUE}[$1/$TOTAL_STEPS]${NC} ${WHITE}$2${NC}"; }
ok()    { echo -e "      ${GREEN}✓${NC}  $*"; }
info()  { echo -e "      ${CYAN}→${NC}  $*"; }
warn()  { echo -e "      ${YELLOW}⚠${NC}  $*"; }
skip()  { echo -e "      ${DIM}–${NC}  $* ${DIM}(already done)${NC}"; }
die()   { echo -e "\n${RED}Error:${NC} $*\n" >&2; exit 1; }

ask() {  # ask "Question?" → exits 0 (yes) or 1 (no)
    local prompt="$1" reply
    echo -en "      ${YELLOW}?${NC}  ${prompt} ${DIM}[Y/n]${NC} "
    read -r reply; reply="${reply:-Y}"
    [[ "$reply" =~ ^[Yy] ]]
}

# ── Header ────────────────────────────────────────────────────────────────────

header() {
    clear
    echo -e "${BOLD}"
    echo "  ╔══════════════════════════════════════════════╗"
    echo "  ║         WebPlayer  —  installer v${VERSION}      ║"
    echo "  ╠══════════════════════════════════════════════╣"
    echo "  ║  MPD web frontend with real-time visualizer  ║"
    echo "  ╚══════════════════════════════════════════════╝"
    echo -e "${NC}"
}

# ── Uninstall ─────────────────────────────────────────────────────────────────

uninstall() {
    header
    echo -e "  ${RED}${BOLD}Uninstalling WebPlayer...${NC}\n"
    systemctl --user stop    webplayer 2>/dev/null && ok "Service stopped"    || true
    systemctl --user disable webplayer 2>/dev/null && ok "Service disabled"   || true
    rm -f  "$SERVICES_DIR/webplayer.service"  && ok "Systemd service removed" || true
    rm -f  "$BIN_DIR/webplayer"               && ok "Launcher removed"        || true
    rm -f  "$APPS_DIR/webplayer.desktop"      && ok "Desktop entry removed"   || true
    rm -rf "$INSTALL_DIR"                     && ok "App files removed"       || true
    systemctl --user daemon-reload
    echo ""
    warn "MPD and its config were left untouched."
    warn "Remove manually if needed: pacman -Rs mpd"
    echo ""
    ok "Uninstall complete."
    exit 0
}

[[ "${1:-}" == "--uninstall" ]] && uninstall

# ─────────────────────────────────────────────────────────────────────────────
# STEP 1 — Detect system
# ─────────────────────────────────────────────────────────────────────────────

header
step 1 "Detecting system"

# Package manager detection
PKG_MANAGER=""
DISTRO_NAME=""
declare -A PKG_INSTALL PKG_CHECK

detect_pkg_manager() {
    if   command -v pacman  &>/dev/null; then
        PKG_MANAGER="pacman"
        DISTRO_NAME="Arch Linux"
        PKG_INSTALL["mpd"]="sudo pacman -S --noconfirm mpd"
        PKG_INSTALL["ffmpeg"]="sudo pacman -S --noconfirm ffmpeg"
        PKG_INSTALL["mutagen"]="sudo pacman -S --noconfirm python-mutagen"
        PKG_CHECK["mpd"]="pacman -Q mpd"
        PKG_CHECK["ffmpeg"]="pacman -Q ffmpeg"
        PKG_CHECK["mutagen"]="pacman -Q python-mutagen"
    elif command -v apt-get &>/dev/null; then
        PKG_MANAGER="apt"
        DISTRO_NAME="Debian/Ubuntu"
        PKG_INSTALL["mpd"]="sudo apt-get install -y mpd"
        PKG_INSTALL["ffmpeg"]="sudo apt-get install -y ffmpeg"
        PKG_INSTALL["mutagen"]="sudo apt-get install -y python3-mutagen"
        PKG_CHECK["mpd"]="dpkg -s mpd"
        PKG_CHECK["ffmpeg"]="dpkg -s ffmpeg"
        PKG_CHECK["mutagen"]="dpkg -s python3-mutagen"
    elif command -v dnf     &>/dev/null; then
        PKG_MANAGER="dnf"
        DISTRO_NAME="Fedora/RHEL"
        PKG_INSTALL["mpd"]="sudo dnf install -y mpd"
        PKG_INSTALL["ffmpeg"]="sudo dnf install -y ffmpeg"
        PKG_INSTALL["mutagen"]="sudo dnf install -y python3-mutagen"
        PKG_CHECK["mpd"]="rpm -q mpd"
        PKG_CHECK["ffmpeg"]="rpm -q ffmpeg"
        PKG_CHECK["mutagen"]="rpm -q python3-mutagen"
    elif command -v zypper  &>/dev/null; then
        PKG_MANAGER="zypper"
        DISTRO_NAME="openSUSE"
        PKG_INSTALL["mpd"]="sudo zypper install -y mpd"
        PKG_INSTALL["ffmpeg"]="sudo zypper install -y ffmpeg"
        PKG_INSTALL["mutagen"]="sudo zypper install -y python-mutagen"
        PKG_CHECK["mpd"]="rpm -q mpd"
        PKG_CHECK["ffmpeg"]="rpm -q ffmpeg"
        PKG_CHECK["mutagen"]="rpm -q python-mutagen"
    else
        die "No supported package manager found (pacman/apt/dnf/zypper).\nInstall mpd, ffmpeg, and python-mutagen manually, then re-run this installer."
    fi
}

detect_pkg_manager
ok "Distro:          $DISTRO_NAME"
ok "Package manager: $PKG_MANAGER"

# Find the app to install (app image preferred, JAR fallback)
APP_IMAGE=""
APP_JAR=""
EXEC_TYPE=""     # "image" or "jar"

# Search relative to the installer's own location
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if   [[ -d "$SCRIPT_DIR/WebPlayer/bin" ]]; then
    APP_IMAGE="$SCRIPT_DIR/WebPlayer"
    EXEC_TYPE="image"
    ok "Found app image: $APP_IMAGE"
elif [[ -f "$SCRIPT_DIR/WebPlayer/bin/WebPlayer" ]]; then
    APP_IMAGE="$SCRIPT_DIR/WebPlayer"
    EXEC_TYPE="image"
    ok "Found app image: $APP_IMAGE"
else
    # Look for JAR
    JAR_GLOB=("$SCRIPT_DIR"/target/webplayer-*.jar "$SCRIPT_DIR"/webplayer-*.jar)
    for j in "${JAR_GLOB[@]}"; do
        [[ -f "$j" ]] && APP_JAR="$j" && break
    done
    if [[ -n "$APP_JAR" ]]; then
        EXEC_TYPE="jar"
        ok "Found JAR:       $APP_JAR"
        command -v java &>/dev/null || \
            die "Java not found. Either install Java 21+ or use the full app-image build.\n  Hint: sudo pacman -S jdk21-openjdk"
        JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d. -f1)
        [[ "$JAVA_VER" -ge 21 ]] 2>/dev/null || \
            die "Java 21+ required. Found version: $JAVA_VER"
        ok "Java version:    $JAVA_VER (OK)"
    else
        die "No WebPlayer app image or JAR found next to this installer.\n  Run './build.sh --app' first, or use './build.sh --release' to create a full bundle."
    fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# STEP 2 — Install system dependencies
# ─────────────────────────────────────────────────────────────────────────────

step 2 "Installing system dependencies"

install_pkg() {
    local pkg="$1"
    if eval "${PKG_CHECK[$pkg]}" &>/dev/null; then
        skip "$pkg"
    else
        info "Installing $pkg..."
        eval "${PKG_INSTALL[$pkg]}" || die "Failed to install $pkg"
        ok "$pkg installed"
    fi
}

install_pkg "mpd"
install_pkg "ffmpeg"
install_pkg "mutagen"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 3 — Configure MPD
# ─────────────────────────────────────────────────────────────────────────────

step 3 "Configuring MPD"

mkdir -p "$MPD_DIR/playlists"
ok "Directories created: $MPD_DIR"

MPD_CONF="$MPD_DIR/mpd.conf"
WRITE_CONF=false
NEED_MPD_RESTART=false

if [[ -f "$MPD_CONF" ]]; then
    HAS_FIFO=false
    HAS_HTTPD=false
    grep -q "/tmp/mpd.fifo" "$MPD_CONF" 2>/dev/null && HAS_FIFO=true
    grep -q '"httpd"'       "$MPD_CONF" 2>/dev/null && HAS_HTTPD=true

    if $HAS_FIFO && $HAS_HTTPD; then
        skip "mpd.conf already has FIFO + HTTP stream configured"

    elif $HAS_FIFO && ! $HAS_HTTPD; then
        warn "mpd.conf has the visualizer FIFO but no HTTP stream output."
        warn "The HTTP stream is needed for the headphones (remote listening) button and sync calibration."
        if ask "Add the HTTP stream output to your existing mpd.conf?"; then
            cp "$MPD_CONF" "${MPD_CONF}.bak"
            cat >> "$MPD_CONF" << 'HTTPD_EOF'

# ── HTTP stream (added by WebPlayer installer) ──────────────────────────────
# Lets any browser on the LAN hear audio via the headphones button, and is
# required for the stream-sync latency calibration feature.
audio_output {
    type            "httpd"
    name            "WebPlayer Stream"
    encoder         "opus"
    port            "8000"
    format          "44100:16:2"
    max_clients     "0"
    always_on       "yes"
    buffer_size     "4096"
}
HTTPD_EOF
            info "Backed up previous config to ${MPD_CONF}.bak"
            ok "HTTP stream output added"
            NEED_MPD_RESTART=true
        else
            warn "Skipped. The headphones/remote-listening feature won't work without it."
        fi

    else
        # No FIFO at all — not a WebPlayer-managed config (or something
        # unusual happened to it). Offer the full optimised config, as before.
        warn "mpd.conf exists but is missing the FIFO output (required for the visualizer)."
        if ask "Overwrite with WebPlayer-optimised config? (your old config will be backed up)"; then
            cp "$MPD_CONF" "${MPD_CONF}.bak"
            info "Backed up to ${MPD_CONF}.bak"
            WRITE_CONF=true
        else
            warn "Skipped. Add the FIFO output manually — see docs."
        fi
    fi
else
    WRITE_CONF=true
fi

if $WRITE_CONF; then
    cat > "$MPD_CONF" << 'MPD_EOF'
# mpd.conf — generated by WebPlayer installer
# Edit freely; the FIFO output at /tmp/mpd.fifo is required for the visualizer,
# and the httpd output is required for the headphones (remote listening) button
# and stream sync calibration.

music_directory     "~/Music"
playlist_directory  "~/.config/mpd/playlists"
db_file             "~/.config/mpd/database"
log_file            "~/.config/mpd/log"
pid_file            "~/.config/mpd/pid"
state_file          "~/.config/mpd/state"
sticker_file        "~/.config/mpd/sticker.sql"

bind_to_address     "0.0.0.0"
port                "6600"

auto_update         "yes"

# ── REQUIRED: FIFO for WebPlayer's real-time FFT visualizer ─────────────────
audio_output {
    type            "fifo"
    name            "WebPlayer Visualizer"
    path            "/tmp/mpd.fifo"
    format          "44100:16:2"
    mixer_type      "null"
}

# ── Primary audio output (PipeWire) ─────────────────────────────────────────
audio_output {
    type            "pipewire"
    name            "PipeWire"
}

# ── HTTP stream — headphones button (remote listening) + sync calibration ───
audio_output {
    type            "httpd"
    name            "WebPlayer Stream"
    encoder         "opus"
    port            "8000"
    format          "44100:16:2"
    max_clients     "0"
    always_on       "yes"
    buffer_size     "4096"
}

replaygain          "auto"
log_level           "default"
MPD_EOF
    ok "mpd.conf written to $MPD_CONF"
    NEED_MPD_RESTART=true
fi

mkdir -p "$HOME/Music"
ok "Music directory: ~/Music"

# Enable + (re)start MPD user service.
systemctl --user daemon-reload
if systemctl --user is-enabled mpd &>/dev/null; then
    skip "MPD service already enabled"
else
    systemctl --user enable mpd
    ok "MPD service enabled"
fi
if systemctl --user is-active mpd &>/dev/null; then
    if $NEED_MPD_RESTART; then
        systemctl --user restart mpd
        ok "MPD service restarted (config changed)"
    else
        skip "MPD service already running"
    fi
else
    systemctl --user start mpd
    ok "MPD service started"
fi

# ─────────────────────────────────────────────────────────────────────────────
# STEP 4 — Install WebPlayer
# ─────────────────────────────────────────────────────────────────────────────

step 4 "Installing WebPlayer"

mkdir -p "$INSTALL_DIR"

if [[ "$EXEC_TYPE" == "image" ]]; then
    info "Copying app image to $INSTALL_DIR…"
    rm -rf "${INSTALL_DIR:?}/app"          # clean previous
    cp -r "$APP_IMAGE" "$INSTALL_DIR/app"
    ok "App image installed: $INSTALL_DIR/app/"
    EXEC_CMD="$INSTALL_DIR/app/bin/WebPlayer"
else
    info "Copying JAR to $INSTALL_DIR…"
    cp "$APP_JAR" "$INSTALL_DIR/webplayer.jar"
    ok "JAR installed: $INSTALL_DIR/webplayer.jar"
    EXEC_CMD="java --enable-native-access=ALL-UNNAMED -jar $INSTALL_DIR/webplayer.jar"
fi

# ─────────────────────────────────────────────────────────────────────────────
# STEP 5 — Create systemd user service
# ─────────────────────────────────────────────────────────────────────────────

step 5 "Setting up autostart"

mkdir -p "$SERVICES_DIR"
cat > "$SERVICES_DIR/webplayer.service" << UNIT
[Unit]
Description=WebPlayer — MPD Web Frontend
Documentation=https://github.com/
After=network-online.target mpd.service
Wants=mpd.service

[Service]
Type=simple
ExecStart=$EXEC_CMD
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
UNIT
ok "Systemd user service created"

systemctl --user daemon-reload
systemctl --user enable webplayer
ok "WebPlayer service enabled (starts on login)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 6 — Create launcher + desktop entry
# ─────────────────────────────────────────────────────────────────────────────

step 6 "Creating launcher"

mkdir -p "$BIN_DIR" "$APPS_DIR"

# Shell launcher — starts service if not running, then opens browser
cat > "$BIN_DIR/webplayer" << 'LAUNCHER'
#!/usr/bin/env bash
if ! systemctl --user is-active --quiet webplayer; then
    echo "Starting WebPlayer service..."
    systemctl --user start webplayer
    # Wait for the HTTP server to be ready (up to 10s)
    for i in {1..20}; do
        curl -sf http://localhost:8080 &>/dev/null && break
        sleep 0.5
    done
fi
xdg-open http://localhost:8080 2>/dev/null \
    || echo "WebPlayer is running at http://localhost:8080"
LAUNCHER
chmod +x "$BIN_DIR/webplayer"
ok "Launcher created: $BIN_DIR/webplayer"

# .desktop entry
cat > "$APPS_DIR/webplayer.desktop" << DESKTOP
[Desktop Entry]
Name=WebPlayer
GenericName=Music Player
Comment=MPD web frontend with real-time visualizer
Exec=$BIN_DIR/webplayer
Icon=audio-x-generic
Terminal=false
Type=Application
Categories=AudioVideo;Audio;Player;
Keywords=mpd;music;player;
DESKTOP
ok "Desktop entry created: $APPS_DIR/webplayer.desktop"

# Make sure ~/.local/bin is on PATH (common issue on fresh installs)
if [[ ":$PATH:" != *":$HOME/.local/bin:"* ]]; then
    warn "~/.local/bin is not in your PATH."
    info "Add this to your shell config (~/.bashrc, ~/.zshrc, ~/.config/fish/config.fish):"
    echo -e "        ${CYAN}export PATH=\"\$HOME/.local/bin:\$PATH\"${NC}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# STEP 7 — Health check + first launch
# ─────────────────────────────────────────────────────────────────────────────

step 7 "Starting WebPlayer"

systemctl --user start webplayer
info "Waiting for server to be ready…"

READY=false
for i in {1..30}; do
    sleep 0.5
    if curl -sf http://localhost:8080 &>/dev/null; then
        READY=true; break
    fi
done

if $READY; then
    ok "WebPlayer is running at http://localhost:8080"
else
    warn "Server didn't respond in 15s — it may still be starting."
    info "Check logs: journalctl --user -u webplayer -f"
fi

if ask "Open WebPlayer in your browser now?"; then
    xdg-open http://localhost:8080 2>/dev/null || true
fi

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo -e "${BOLD}${GREEN}"
echo "  ╔══════════════════════════════════════════════╗"
echo "  ║          Installation complete!  ✓           ║"
echo "  ╠══════════════════════════════════════════════╣"
echo -e "  ║  Open:     ${CYAN}http://localhost:8080${GREEN}             ║"
echo -e "  ║  Launch:   ${CYAN}webplayer${GREEN}                         ║"
echo -e "  ║  Logs:     ${CYAN}journalctl --user -u webplayer${GREEN}    ║"
echo -e "  ║  Stop:     ${CYAN}systemctl --user stop webplayer${GREEN}   ║"
echo -e "  ║  Remove:   ${CYAN}./install.sh --uninstall${GREEN}          ║"
echo "  ╚══════════════════════════════════════════════╝"
echo -e "${NC}"