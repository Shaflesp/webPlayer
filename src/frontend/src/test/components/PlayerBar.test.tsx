import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PlayerBar } from '../../components/PlayerBar';
import { useStore, DEFAULT_SETTINGS } from '../../store';

// jsdom's HTMLMediaElement.play() throws "Not implemented" by default —
// there is no real media backend, so every test that reaches an Audio
// element's .play() call needs this stubbed first.
beforeEach(() => {
  vi.spyOn(window.HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(window.HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  cleanup();
});

function setStreamUrl(url: string) {
  useStore.getState().setSettings({ ...DEFAULT_SETTINGS, 'stream.url': url });
}

/**
 * Overrides window.location.hostname for a test.
 *
 * A plain `window.location = {...}` assignment fights TypeScript here:
 * `window.location` is typed `Location`, whose methods (assign, reload,
 * replace, toString) live on Location.prototype — object spread only
 * copies own enumerable data properties, so `{...window.location}` is
 * missing those methods at runtime even though TypeScript still checks the
 * spread's type against Location's full declared shape, producing a
 * mismatch on reassignment.
 *
 * Object.defineProperty sidesteps this entirely: PropertyDescriptor's
 * `value` field is typed `any`, so no structural check applies — and it's
 * also the more correct technique regardless, since `window.location` isn't
 * a simple writable data property in real browsers either.
 *
 * PlayerBar only ever reads `.hostname`, so nothing else needs stubbing.
 */
function setHostname(hostname: string) {
  Object.defineProperty(window, 'location', {
    value: { hostname },
    writable: true,
    configurable: true,
  });
}

function restoreLocalhost() {
  setHostname('localhost'); // jsdom's own default test URL
}

afterEach(() => {
  restoreLocalhost();
});

describe('PlayerBar — same-device detection', () => {
  beforeEach(() => {
    useStore.setState({ settings: DEFAULT_SETTINGS });
  });

  it("disables the headphones button by default (jsdom's test URL is localhost)", () => {
    setStreamUrl('http://192.168.1.42:8000');
    render(<PlayerBar />);

    const btn = screen.getByTitle(/already playing on this device/i);
    expect(btn).toBeDisabled();
  });

  it('enables the headphones button when the page hostname is not localhost', () => {
    setHostname('remote-laptop');
    setStreamUrl('http://192.168.1.42:8000');
    render(<PlayerBar />);

    const btn = screen.getByTitle(/play on this device/i);
    expect(btn).not.toBeDisabled();
  });
});

describe('PlayerBar — sync calibration', () => {
  beforeEach(() => {
    useStore.setState({ settings: DEFAULT_SETTINGS });
    // All calibration tests need isSameDevice=false to interact with the button.
    setHostname('remote-laptop');
    setStreamUrl('http://192.168.1.42:8000');
    vi.useFakeTimers();
  });

  /**
   * THE core regression test for this component. calibrateSyncDelay always
   * returns HTTP 200 even on failure (see StatusControllerTest's matching
   * backend test) — the frontend previously trusted fetch().then() firing
   * as proof of success, silently claiming "Synced" even when the server
   * had done nothing. This confirms the fix: the json body's `ok` field is
   * actually read, and a false value surfaces the real error message.
   */
  it('shows the real server error instead of a false success when calibration fails server-side', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((url, init) => {
      if (url === '/StatusServlet' && init?.method === 'POST') {
        const body = JSON.parse(init.body as string);
        if (body.action === 'calibrateSyncDelay') {
          return Promise.resolve(new Response(JSON.stringify({
            ok: false,
            error: 'PipeWire restarted but sink never appeared',
          })));
        }
      }
      return Promise.resolve(new Response('{}'));
    });

    render(<PlayerBar />);

    const headphonesBtn = screen.getByTitle(/play on this device/i);
    await userEvent.click(headphonesBtn, { advanceTimers: vi.advanceTimersByTimeAsync });

    // 3s stabilize delay, then 5x 1s samples = 8s to complete measurement
    await vi.advanceTimersByTimeAsync(3000);
    await vi.advanceTimersByTimeAsync(5000);
    // Let the fetch().then()/.json() promise chain fully settle
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);

    expect(screen.getByText('PipeWire restarted but sink never appeared')).toBeInTheDocument();
    expect(screen.queryByText(/^Synced/)).not.toBeInTheDocument();
  });

  it('shows the synced status when the server genuinely confirms success', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((url, init) => {
      if (url === '/StatusServlet' && init?.method === 'POST') {
        const body = JSON.parse(init.body as string);
        if (body.action === 'calibrateSyncDelay') {
          return Promise.resolve(new Response(JSON.stringify({ ok: true, delaySeconds: body.delaySeconds })));
        }
      }
      return Promise.resolve(new Response('{}'));
    });

    render(<PlayerBar />);

    const headphonesBtn = screen.getByTitle(/play on this device/i);
    await userEvent.click(headphonesBtn, { advanceTimers: vi.advanceTimersByTimeAsync });

    await vi.advanceTimersByTimeAsync(3000);
    await vi.advanceTimersByTimeAsync(5000);
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);

    expect(screen.getByText(/^Synced/)).toBeInTheDocument();
  });

  it('never calls the server when calibration measures an unrealistic value, and shows a clear message instead', async () => {
    // Regression test for the -605.5s bug: a stale timestamp origin produced
    // a nonsensical measurement. The sanity clamp must catch this BEFORE
    // ever reaching the server, rather than writing garbage to mpd.conf.
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response('{}'));

    render(<PlayerBar />);
    const headphonesBtn = screen.getByTitle(/play on this device/i);
    await userEvent.click(headphonesBtn, { advanceTimers: vi.advanceTimersByTimeAsync });

    // Force an enormous, implausible gap (mimicking the stale-origin bug) by
    // advancing far beyond any realistic buffering delay before measurement.
    await vi.advanceTimersByTimeAsync(3000);
    await vi.advanceTimersByTimeAsync(600_000); // ~10 minutes — well past the 15s sanity ceiling
    await vi.advanceTimersByTimeAsync(0);

    const calibrateCalls = fetchSpy.mock.calls.filter(([url, init]) => {
      if (url !== '/StatusServlet' || (init as RequestInit)?.method !== 'POST') return false;
      const body = JSON.parse((init as RequestInit).body as string);
      return body.action === 'calibrateSyncDelay';
    });
    expect(calibrateCalls).toHaveLength(0);
    expect(screen.getByText(/isn't realistic/i)).toBeInTheDocument();
  });
});