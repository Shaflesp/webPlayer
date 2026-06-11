import { useState, useCallback, cloneElement, type ReactElement } from 'react';
import { createPortal } from 'react-dom';

interface Props {
  text: string;
  children: ReactElement<Record<string, unknown>>;
}

interface TipPos { x: number; y: number; flip: boolean; }

/**
 * Wraps any element with a tooltip rendered via createPortal into document.body.
 * This means z-index and stacking contexts are completely irrelevant — the tip
 * is always painted above everything else.
 */
export function Tooltip({ text, children }: Props) {
  const [pos, setPos] = useState<TipPos | null>(null);

  const show = useCallback((e: React.MouseEvent) => {
    const r    = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const flip = r.top < 52;
    setPos({ x: r.left + r.width / 2, y: flip ? r.bottom : r.top, flip });
  }, []);

  const hide = useCallback(() => setPos(null), []);

  return (
    <>
      {cloneElement(children, { onMouseEnter: show, onMouseLeave: hide })}
      {pos && createPortal(
        <div
          className={`g-tip${pos.flip ? ' flip' : ''}`}
          style={{ left: pos.x, top: pos.y }}
        >
          {text}
        </div>,
        document.body,
      )}
    </>
  );
}
