import { useRef, forwardRef, memo } from 'react';
import { useStore } from '../store';
import { useVisualizer } from '../hooks/useVisualizer';
import { artUrl, basename } from '../api';

// ── VinylDisc ─────────────────────────────────────────────────────────────────

interface VinylProps {
  fileUri: string | null;
  isPlaying: boolean;
  speed: string;
}

/** Memoised — only re-renders when the song file or play state changes. */
const VinylDisc = memo(forwardRef<HTMLDivElement, VinylProps>(
  ({ fileUri, isPlaying, speed }, ref) => {
    const url = fileUri ? artUrl(fileUri) : null;

    return (
      <div className="vinyl-wrap">
        {/* z-index 1: the disc itself */}
        <div
          ref={ref}
          className={`vinyl${isPlaying ? ' playing' : ''}`}
          style={{ animationDuration: `${speed}s` }}
        >
          <div className="vinyl-art">
            {url ? (
              <>
                <img src={url} alt="" />
                <div className="vinyl-hole" />
              </>
            ) : (
              <div className="art-placeholder">
                <i className="fas fa-music" />
              </div>
            )}
          </div>
        </div>

        {/* z-index 2: tonearm ON TOP of the disc */}
        <div className="tonearm">
          <svg viewBox="0 0 60 160" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="48" cy="12" r="8" fill="#2a2a2a" stroke="#444" strokeWidth="1.5" />
            <circle cx="48" cy="12" r="3"  fill="#555" />
            <line x1="48" y1="20" x2="12" y2="148"
                  stroke="#3a3a3a" strokeWidth="3" strokeLinecap="round" />
            <rect x="5" y="144" width="14" height="5" rx="2"
                  fill="#2a2a2a" stroke="#444" strokeWidth="1" />
            <line x1="12" y1="149" x2="12" y2="156"
                  stroke="#555" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
        </div>
      </div>
    );
  }
));
VinylDisc.displayName = 'VinylDisc';

// ── NowPlaying ────────────────────────────────────────────────────────────────

export function NowPlaying() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const vinylRef  = useRef<HTMLDivElement>(null);

  const currentSong = useStore(s => s.currentSong);
  const status      = useStore(s => s.status);
  const settings    = useStore(s => s.settings);

  useVisualizer(canvasRef, vinylRef);

  const file      = currentSong.file ?? null;
  const title     = currentSong.Title     ?? (file ? basename(file) : 'Nothing playing');
  const artist    = currentSong.Artist    ?? currentSong.AlbumArtist ?? '—';
  const album     = currentSong.Album     ?? '';
  const bgOpacity = parseFloat(settings['ui.bgOpacity'] ?? '0.20');
  const speed     = settings['ui.vinylSpeed'] ?? '6';

  return (
    <section className="now-playing">
      {/* Visualizer canvas — z-index -1, behind all normal-flow children */}
      <canvas ref={canvasRef} className="viz-canvas" />

      {/* Blurred background art */}
      {file && (
        <div
          className="bg-art"
          style={{ backgroundImage: `url('${artUrl(file)}')`, opacity: bgOpacity }}
        />
      )}
      <div className="bg-overlay" />

      {/* Vinyl disc — z-index 1 */}
      <VinylDisc
        ref={vinylRef}
        fileUri={file}
        isPlaying={status.state === 'play'}
        speed={speed}
      />

      {/* Track metadata */}
      <div className="np-meta">
        <div className="np-title">{title}</div>
        <div className="np-artist">{artist}</div>
        {album && <div className="np-album">{album}</div>}
      </div>
    </section>
  );
}
