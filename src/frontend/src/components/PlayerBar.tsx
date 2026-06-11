import { useState, useEffect, useCallback } from 'react';
import { useStore } from '../store';
import { Tooltip } from './Tooltip.tsx';
import {
  play, pause, resume, next, previous,
  seekCur, setVol, toggleRandom, toggleRepeat, toggleSingle,
} from '../api';

// ── Seek bar ──────────────────────────────────────────────────────────────────

function SeekBar() {
  const status   = useStore(s => s.status);
  const [seeking,   setSeeking]   = useState(false);
  const [localFrac, setLocalFrac] = useState(0);

  // Sync from MPD when not dragging
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
  const vol             = useStore(s => s.status.volume);
  const [dragging, setDragging]   = useState(false);
  const [localVol, setLocalVol]   = useState(100);
  const [prevVol,  setPrevVol]    = useState(75);

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

export function PlayerBar() {
  const status = useStore(s => s.status);

  const handlePlay = useCallback(async () => {
    if      (status.state === 'play')  await pause();
    else if (status.state === 'pause') await resume();
    else                               await play();
  }, [status.state]);

  const playIcon = status.state === 'play' ? 'fa-pause' : 'fa-play';

  return (
    <div className="player-bar">
      <SeekBar />

      <div className="ctrl-row">
        {/* Left: mode toggles */}
        <div className="side-btns">
          <Tooltip text="Shuffle (S)">
            <button
              className={`ctrl-btn toggle-btn${status.random  ? ' active' : ''}`}
              onClick={toggleRandom}
            ><i className="fas fa-shuffle" /></button>
          </Tooltip>
          <Tooltip text="Repeat (R)">
            <button
              className={`ctrl-btn toggle-btn${status.repeat  ? ' active' : ''}`}
              onClick={toggleRepeat}
            ><i className="fas fa-repeat" /></button>
          </Tooltip>
          <Tooltip text="Single">
            <button
              className={`ctrl-btn toggle-btn${status.single  ? ' active' : ''}`}
              onClick={toggleSingle}
            ><span className="btn-label-text">1</span></button>
          </Tooltip>
        </div>

        {/* Centre: transport */}
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

        {/* Right: volume */}
        <Volume />
      </div>
    </div>
  );
}

// ── Util ──────────────────────────────────────────────────────────────────────

function fmt(s: number): string {
  const sec = Math.floor(s) || 0;
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
}
