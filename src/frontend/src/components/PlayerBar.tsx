import { useState, useEffect, useCallback, useRef } from 'react';
import { useStore } from '../store';
import { Tooltip } from './Tooltip.tsx';
import {
  play, pause, resume, next, previous,
  seekCur, setVol, toggleRandom, toggleRepeat, toggleSingle,
} from '../api';
import * as React from "react";

// ── Seek bar ──────────────────────────────────────────────────────────────────

function SeekBar() {
  const status   = useStore(s => s.status);
  const [seeking,   setSeeking]   = useState(false);
  const [localFrac, setLocalFrac] = useState(0);

  useEffect(() => {
    if (!seeking && status.duration > 0)
      setLocalFrac(status.elapsed / status.duration);
  }, [status.elapsed, status.duration, seeking]);

  const pct = (localFrac * 100).toFixed(2) + '%';

  const onMouseDown = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    setSeeking(true);
    const r = e.currentTarget.getBoundingClientRect();
    setLocalFrac(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)));
  }, []);

  const onMouseMove = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    if (!seeking) return;
    const r = e.currentTarget.getBoundingClientRect();
    setLocalFrac(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)));
  }, [seeking]);

  const onMouseUp = useCallback(async (e: React.MouseEvent<HTMLDivElement>) => {
    if (!seeking) return;
    const r    = e.currentTarget.getBoundingClientRect();
    const frac = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    setLocalFrac(frac);
    setSeeking(false);
    await seekCur(frac * status.duration);
  }, [seeking, status.duration]);

  return (
      <div className="seek-row">
        <span className="time-label">{fmt(localFrac * status.duration)}</span>
        <div
            className="seek-track"
            onMouseDown={onMouseDown}
            onMouseMove={onMouseMove}
            onMouseUp={onMouseUp}
            onMouseLeave={e => { if (seeking) onMouseUp(e as React.MouseEvent<HTMLDivElement>); }}
        >
          <div className="seek-fill"  style={{ width: pct }} />
          <div className="seek-thumb" style={{ left:  pct }} />
        </div>
        <span className="time-label">{fmt(status.duration)}</span>
      </div>
  );
}

// ── Volume ────────────────────────────────────────────────────────────────────

function Volume() {
  const vol = useStore(s => s.status.volume);
  const [dragging, setDragging] = useState(false);
  const [localVol, setLocalVol] = useState(100);
  const [prevVol,  setPrevVol]  = useState(75);

  useEffect(() => { if (!dragging) setLocalVol(vol); }, [vol, dragging]);

  const onChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const v = parseInt(e.target.value);
    setLocalVol(v);
    setDragging(true);
    setVol(v);
  }, []);

  const onRelease = useCallback(() => setDragging(false), []);

  const mute = useCallback(async () => {
    if (localVol > 0) { setPrevVol(localVol); await setVol(0); }
    else              { await setVol(prevVol || 75); }
  }, [localVol, prevVol]);

  const icon = localVol === 0
      ? 'fa-volume-xmark'
      : localVol < 50
          ? 'fa-volume-low'
          : 'fa-volume-high';

  return (
      <div className="side-btns volume-side">
        <Tooltip text="Mute">
          <i className={`fas ${icon} vol-icon`} onClick={mute} />
        </Tooltip>
        <input
            type="range" className="vol-slider"
            min={0} max={100} value={localVol}
            onChange={onChange}
            onMouseUp={onRelease} onTouchEnd={onRelease}
        />
        <span className="vol-value">{localVol}</span>
      </div>
  );
}

// ── PlayerBar ─────────────────────────────────────────────────────────────────

type CalibState = 'idle' | 'stabilizing' | 'measuring' | 'applying' | 'done';

export function PlayerBar() {
  const status    = useStore(s => s.status);
  const streamUrl = useStore(s => s.settings['stream.url'] ?? '');

  const audioRef         = useRef<HTMLAudioElement | null>(null);
  const suppressErrorRef = useRef(false);   // guards onerror during ANY expected disconnect
  const skipSeekRef      = useRef(false);   // suppresses canplay seek during calibration
  const playStartRef     = useRef<number | null>(null);

  const [localPlaying,    setLocalPlaying]    = useState(false);
  const [localError,      setLocalError]      = useState<string | null>(null);
  const [calibState,      setCalibState]      = useState<CalibState>('idle');
  const [measuredLatency, setMeasuredLatency] = useState<number | null>(null);

  const isSameDevice =
      window.location.hostname === 'localhost' ||
      window.location.hostname === '127.0.0.1';

  // ── Sync <audio> with MPD status ─────────────────────────────────────────
  useEffect(() => {
    if (!audioRef.current || !localPlaying) return;
    if (status.state === 'play') {
      if (audioRef.current.paused) {
        suppressErrorRef.current = true;
        const url = audioRef.current.src;
        audioRef.current.src = '';
        audioRef.current.src = url;
        suppressErrorRef.current = false;
        audioRef.current.play().catch(() => {});
      }
    } else {
      audioRef.current.pause();
    }
  }, [status.state, localPlaying]);

  // ── Audio element factory ─────────────────────────────────────────────────
  const createAudio = useCallback((url: string, calibrating = false) => {
    const audio = new Audio(url);
    audio.volume = 1.0;

    audio.addEventListener('canplay', () => {
      if (skipSeekRef.current) return;
      if (!audio.buffered.length) return;
      const end = audio.buffered.end(audio.buffered.length - 1);
      if (end - audio.currentTime > 1) audio.currentTime = end - 0.1;
    }, { once: true });

    audio.onerror = () => {
      if (suppressErrorRef.current) return;
      setLocalError('Stream unreachable — check the URL in Settings and that MPD is running');
      audioRef.current?.pause();
      audioRef.current = null;
      setLocalPlaying(false);
      setCalibState('idle');
    };

    if (calibrating) playStartRef.current = Date.now();

    audio.play().catch(err => {
      if (err?.name === 'AbortError') return;
      if (suppressErrorRef.current) return;
      setLocalError('Could not start audio: ' + (err?.message ?? 'unknown error'));
      audioRef.current = null;
      setLocalPlaying(false);
    });

    return audio;
  }, []);

  // ── Latency calibration ───────────────────────────────────────────────────

  const runCalibration = useCallback((url: string) => {
    skipSeekRef.current = true;
    setCalibState('stabilizing');
    setMeasuredLatency(null);
    playStartRef.current = null;

    audioRef.current = createAudio(url, /* calibrating */ true);
    setLocalPlaying(true);

    const stabilizeTimeout = setTimeout(() => {
      setCalibState('measuring');
      const samples: number[] = [];

      const measureInterval = setInterval(() => {
        if (!audioRef.current || !playStartRef.current) return;
        const wallElapsed  = (Date.now() - playStartRef.current) / 1000;
        const audioElapsed = audioRef.current.currentTime;
        samples.push(wallElapsed - audioElapsed);

        if (samples.length >= 5) {
          clearInterval(measureInterval);
          skipSeekRef.current = false;

          const avg = samples.reduce((a, b) => a + b, 0) / samples.length;
          const rounded = Math.round(avg * 10) / 10;

          if (rounded < 0.3 || rounded > 15) {
            setCalibState('idle');
            setLocalError(
                `Measurement came back as ${rounded}s, which isn't realistic — ` +
                `something went wrong. Try disconnecting and reconnecting.`
            );
            return;
          }

          setMeasuredLatency(rounded);
          setCalibState('applying');
          suppressErrorRef.current = true;

          fetch('/StatusServlet', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'calibrateSyncDelay', delaySeconds: rounded }),
          }).then(() => {
            setCalibState('done');
            setTimeout(() => {
              audioRef.current?.pause();
              if (audioRef.current) audioRef.current.src = '';
              audioRef.current = createAudio(url);   // plain reconnect, not calibrating again
              suppressErrorRef.current = false;
            }, 3000);
          }).catch(() => {
            suppressErrorRef.current = false;
            setCalibState('idle');
            setLocalError('Failed to apply sync delay — check server logs');
          });
        }
      }, 1000);
    }, 3000);

    return () => { clearTimeout(stabilizeTimeout); skipSeekRef.current = false; };
  }, [createAudio]);

  // ── Toggle local audio ────────────────────────────────────────────────────
  const toggleLocalAudio = useCallback(() => {
    if (!streamUrl || isSameDevice) return;

    if (localPlaying) {
      if (audioRef.current) {
        suppressErrorRef.current = true;
        audioRef.current.pause();
        audioRef.current.src = '';
        suppressErrorRef.current = false;
        audioRef.current = null;
      }
      setLocalPlaying(false);
      setLocalError(null);
      setCalibState('idle');
    } else {
      const url = /^https?:\/\//i.test(streamUrl) ? streamUrl : 'http://' + streamUrl;
      setLocalError(null);
      runCalibration(url);
    }
  }, [localPlaying, streamUrl, isSameDevice, runCalibration]);

  // Clean up on unmount
  useEffect(() => {
    return () => {
      suppressErrorRef.current = true;
      audioRef.current?.pause();
      audioRef.current = null;
    };
  }, []);

  const handlePlay = useCallback(async () => {
    if (status.state === 'play') {
      await pause();
      audioRef.current?.pause();
    } else if (status.state === 'pause') {
      await resume();
      if (audioRef.current && localPlaying) {
        suppressErrorRef.current = true;
        const url = audioRef.current.src;
        audioRef.current.src = '';
        audioRef.current.src = url;
        suppressErrorRef.current = false;
        audioRef.current.play().catch(() => {});
      }
    } else {
      await play();
    }
  }, [status.state, localPlaying]);

  const playIcon = status.state === 'play' ? 'fa-pause' : 'fa-play';
  const headphonesTooltip = isSameDevice
      ? 'Already playing on this device — headphones mode is for remote listeners'
      : streamUrl
          ? (localPlaying ? 'Stop playing on this device' : 'Play on this device (auto-syncs with speakers)')
          : 'Set a Stream URL in Settings to enable this';

  const calibLabel: Record<Exclude<CalibState, 'idle'>, string> = {
    stabilizing: 'Connecting & buffering…',
    measuring:   'Syncing with speakers…',
    applying:    'Applying sync (MPD restarting)…',
    done:        measuredLatency !== null ? `Synced (${measuredLatency}s delay)` : 'Synced',
  };

  return (
      <div className="player-bar">
        <SeekBar />

        {localError && (
            <div style={{ fontSize: 11, color: '#f87171', textAlign: 'center',
              marginBottom: 2, padding: '0 16px' }}>
              <i className="fas fa-triangle-exclamation" style={{ marginRight: 5 }} />
              {localError}
            </div>
        )}

        {localPlaying && calibState !== 'idle' && (
            <div style={{ fontSize: 11, color: 'var(--text-sub)', textAlign: 'center',
              marginBottom: 2, padding: '0 16px' }}>
              <i className={`fas ${calibState === 'done' ? 'fa-check' : 'fa-spinner fa-spin'}`}
                 style={{ marginRight: 5, color: calibState === 'done' ? '#4ade80' : 'var(--accent)' }} />
              {calibLabel[calibState]}
            </div>
        )}

        <div className="ctrl-row">
          <div className="side-btns">
            <Tooltip text="Shuffle (S)">
              <button
                  className={`ctrl-btn toggle-btn${status.random ? ' active' : ''}`}
                  onClick={toggleRandom}
              ><i className="fas fa-shuffle" /></button>
            </Tooltip>
            <Tooltip text="Repeat (R)">
              <button
                  className={`ctrl-btn toggle-btn${status.repeat ? ' active' : ''}`}
                  onClick={toggleRepeat}
              ><i className="fas fa-repeat" /></button>
            </Tooltip>
            <Tooltip text="Single">
              <button
                  className={`ctrl-btn toggle-btn${status.single ? ' active' : ''}`}
                  onClick={toggleSingle}
              ><span className="btn-label-text">1</span></button>
            </Tooltip>

            <Tooltip text={headphonesTooltip}>
              <button
                  className={`ctrl-btn toggle-btn${localPlaying ? ' active' : ''}`}
                  onClick={toggleLocalAudio}
                  style={{ opacity: (streamUrl && !isSameDevice) ? 1 : 0.35 }}
                  disabled={isSameDevice || (calibState !== 'idle' && calibState !== 'done')}
              >
                <i className="fas fa-headphones" />
              </button>
            </Tooltip>
          </div>

          <div className="transport">
            <Tooltip text="Previous (←)">
              <button className="ctrl-btn nav-btn" onClick={previous}>
                <i className="fas fa-backward-step" />
              </button>
            </Tooltip>
            <Tooltip text="Play / Pause (Space)">
              <button className="ctrl-btn play-btn" onClick={handlePlay}>
                <i className={`fas ${playIcon}`} />
              </button>
            </Tooltip>
            <Tooltip text="Next (→)">
              <button className="ctrl-btn nav-btn" onClick={next}>
                <i className="fas fa-forward-step" />
              </button>
            </Tooltip>
          </div>

          <Volume />
        </div>
      </div>
  );
}

function fmt(s: number): string {
  const sec = Math.floor(s) || 0;
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
}