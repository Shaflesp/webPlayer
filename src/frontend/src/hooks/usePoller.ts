import { useEffect, useRef } from 'react';
import { useStore } from '../store';
import { fetchNowPlaying, fetchQueue } from '../api';

/**
 * Polls MPD every `pollInterval` ms.
 * The queue is only re-fetched when playlistversion changes — not every tick.
 * Pause-on-close sendBeacon is also registered here.
 */
export function usePoller() {
  const setStatus      = useStore(s => s.setStatus);
  const setCurrentSong = useStore(s => s.setCurrentSong);
  const setQueue       = useStore(s => s.setQueue);
  const setError       = useStore(s => s.setError);
  const settings       = useStore(s => s.settings);

  const prevVersionRef = useRef(-1);
  const intervalMs     = parseInt(settings['player.pollInterval'] ?? '1000');

  // ── Polling ──────────────────────────────────────────────────────────────
  useEffect(() => {
    let active = true;

    async function poll() {
      try {
        const { status, song } = await fetchNowPlaying();
        if (!active) return;

        setStatus(status);
        setCurrentSong(song ?? {});
        setError(null);

        // Only hit the queue endpoint when the server says it changed
        if (status.playlistversion !== prevVersionRef.current) {
          prevVersionRef.current = status.playlistversion;
          const queue = await fetchQueue();
          if (active) setQueue(queue);
        }
      } catch {
        if (active) setError('Cannot reach MPD');
      }
    }

    poll();
    const id = setInterval(poll, intervalMs);
    return () => { active = false; clearInterval(id); };
  }, [intervalMs]); // recreates the interval only when the user changes it

  // ── Pause on close ────────────────────────────────────────────────────────
  const statusState    = useStore(s => s.status.state);
  const pauseOnClose   = settings['player.pauseOnClose'] === 'true';

  useEffect(() => {
    const handler = () => {
      if (pauseOnClose && statusState === 'play') {
        navigator.sendBeacon(
          '/MPDServlet',
          new Blob([JSON.stringify({ action: 'pause' })], { type: 'application/json' }),
        );
      }
    };
    window.addEventListener('pagehide', handler);
    return () => window.removeEventListener('pagehide', handler);
  }, [pauseOnClose, statusState]);
}
