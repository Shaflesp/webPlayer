import { useRef, forwardRef, memo } from 'react';
import { useStore } from '../store';
import { useVisualizer } from '../hooks/useVisualizer';
import { artUrl, basename } from '../api';

// ── VinylDisc ─────────────────────────────────────────────────────────────────

interface VinylProps {
    fileUri:   string | null;
    isPlaying: boolean;
    speed:     string;
}

/**
 * The ref is forwarded to .vinyl-wrap (the static, non-rotating container).
 * Previously it pointed to .vinyl (the spinning div), whose getBoundingClientRect()
 * oscillates every half-revolution on a square element — causing the FFT ellipse
 * radius to pulse in sync with the vinyl speed.
 */
const VinylDisc = memo(forwardRef<HTMLDivElement, VinylProps>(
    ({ fileUri, isPlaying, speed }, ref) => {
        const url = fileUri ? artUrl(fileUri) : null;

        return (
            // ref HERE — on the static wrapper, not the rotating child
            <div className="vinyl-wrap" ref={ref}>

                {/* z-index 1: the spinning disc */}
                <div
                    className={`vinyl${isPlaying ? ' playing' : ''}`}
                    style={{ animationDuration: `${speed}s` }}
                >
                    <div className="vinyl-art">
                        {url ? (
                            <>
                                <img src={url} alt=""
                                     onError={e => {
                                         e.currentTarget.style.display = 'none';
                                         const p = e.currentTarget.nextElementSibling as HTMLElement | null;
                                         if (p) p.style.display = 'flex';
                                     }} />
                                <div className="art-placeholder" style={{ display: 'none' }}>
                                    <i className="fas fa-music" />
                                </div>
                                <div className="vinyl-hole" />
                            </>
                        ) : (
                            <div className="art-placeholder">
                                <i className="fas fa-music" />
                            </div>
                        )}
                    </div>
                </div>

                {/*
          z-index 2: tonearm ON TOP of the disc.
          .tonearm pivots at its top-right corner (the physical pivot point in the SVG).
          When playing it swings inward; when stopped/paused it lifts back out.
        */}
                <div className={`tonearm${isPlaying ? ' playing' : ''}`}>
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
    const vinylRef  = useRef<HTMLDivElement>(null);   // points to .vinyl-wrap

    const currentSong = useStore(s => s.currentSong);
    const status      = useStore(s => s.status);
    const settings    = useStore(s => s.settings);

    useVisualizer(canvasRef, vinylRef);

    const file      = currentSong.file ?? null;
    const title     = currentSong.Title  ?? (file ? basename(file) : 'Nothing playing');
    const artist    = currentSong.Artist ?? currentSong.AlbumArtist ?? '—';
    const album     = currentSong.Album  ?? '';
    const bgOpacity = parseFloat(settings['ui.bgOpacity'] ?? '0.20');
    const speed     = settings['ui.vinylSpeed'] ?? '6';

    return (
        <section className="now-playing">
            {/* Blurred background art */}
            {file && (
                <div
                    className="bg-art"
                    style={{ backgroundImage: `url('${artUrl(file)}')`, opacity: bgOpacity }}
                />
            )}
            <div className="bg-overlay" />

            {
      }
            <canvas ref={canvasRef} className="viz-canvas" />

            <VinylDisc
                ref={vinylRef}
                fileUri={file}
                isPlaying={status.state === 'play'}
                speed={speed}
            />

            <div className="np-meta">
                <div className="np-title">{title}</div>
                <div className="np-artist">{artist}</div>
                {album && <div className="np-album">{album}</div>}
            </div>
        </section>
    );
}