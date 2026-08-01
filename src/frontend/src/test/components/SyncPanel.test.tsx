import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SyncPanel } from '../../components/SyncPanel';
import { useStore } from '../../store';
import { FakeEventSource } from '../FakeEventSource';

// SyncPanel constructs `new EventSource(...)` directly — jsdom has no native
// implementation at all, so this stub is required for the module to even load
// without throwing when that line executes.
vi.stubGlobal('EventSource', FakeEventSource);

function openPanel() {
  useStore.getState().setSyncOpen(true);
}

beforeEach(() => {
  FakeEventSource.reset();
  useStore.setState({ syncOpen: false });
  vi.restoreAllMocks();
});

afterEach(() => {
  useStore.setState({ syncOpen: false });
});

describe('SyncPanel', () => {
  it('renders nothing when closed', () => {
    render(<SyncPanel />);
    expect(screen.queryByText('Import from YouTube')).not.toBeInTheDocument();
  });

  it('renders the panel when open, and loads the playlist list', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((url) => {
      const u = String(url);
      if (u.includes('action=list')) {
        return Promise.resolve(new Response(JSON.stringify([
          { name: 'My Playlist', url: 'https://youtube.com/x', lastSynced: '2026-01-01 00:00:00', tracks: 12 },
        ])));
      }
      if (u === '/StatusServlet') {
        return Promise.resolve(new Response(JSON.stringify({
          ytdlp: { ok: true, path: '', note: '', version: '2026.01.01' },
        })));
      }
      return Promise.resolve(new Response('{}'));
    });

    openPanel();
    render(<SyncPanel />);

    expect(await screen.findByText('My Playlist')).toBeInTheDocument();
    expect(screen.getByText('12 tracks')).toBeInTheDocument();
  });

  /**
   * THE core regression test. Sequence being reproduced:
   *   1. Server sends 'done' with data='ok' → should show success.
   *   2. The SSE connection then closes (as it always does right after
   *      'done'), firing the browser's onerror — this must NOT overwrite
   *      the correct 'ok' state with 'error'.
   *
   * Before the doneReceived-flag fix, step 2 unconditionally flipped the
   * state to 'error' even after a fully successful sync.
   */
  it('does not let the benign post-done connection-close overwrite a successful sync with an error', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((url, init) => {
      const u = String(url);
      if (init?.method === 'POST' && u === '/SyncServlet') {
        return Promise.resolve(new Response(JSON.stringify({ jobId: 'job123' })));
      }
      if (u.includes('action=list')) return Promise.resolve(new Response('[]'));
      return Promise.resolve(new Response('{}'));
    });

    openPanel();
    render(<SyncPanel />);

    const input = screen.getByPlaceholderText(/youtube\.com\/playlist/i);
    await userEvent.type(input, 'https://youtube.com/playlist?list=abc');
    await userEvent.click(screen.getByRole('button', { name: /^sync$/i }));

    const es = await waitFor(() => {
      const instance = FakeEventSource.latest();
      if (!instance) throw new Error('EventSource not yet constructed');
      return instance;
    });

    // Server signals success...
    es.emitNamed('done', 'ok');
    // ...then the connection closes, as it always does, firing onerror.
    // This must be ignored — the doneReceived flag is what makes that happen.
    es.emitError();

    expect(await screen.findByText(/sync complete/i)).toBeInTheDocument();
    expect(screen.queryByText(/sync failed/i)).not.toBeInTheDocument();
  });

  it('shows a failure message when done fires with data=error (a genuine failure, not the benign-close case)', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((url, init) => {
      const u = String(url);
      if (init?.method === 'POST' && u === '/SyncServlet') {
        return Promise.resolve(new Response(JSON.stringify({ jobId: 'job456' })));
      }
      if (u.includes('action=list')) return Promise.resolve(new Response('[]'));
      return Promise.resolve(new Response('{}'));
    });

    openPanel();
    render(<SyncPanel />);

    const input = screen.getByPlaceholderText(/youtube\.com\/playlist/i);
    await userEvent.type(input, 'https://youtube.com/playlist?list=bad');
    await userEvent.click(screen.getByRole('button', { name: /^sync$/i }));

    const es = await waitFor(() => {
      const instance = FakeEventSource.latest();
      if (!instance) throw new Error('EventSource not yet constructed');
      return instance;
    });

    es.emitNamed('done', 'error');

    expect(await screen.findByText(/sync failed/i)).toBeInTheDocument();
  });

  it('parses "Downloading item X of Y" log lines into the progress bar', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((url, init) => {
      const u = String(url);
      if (init?.method === 'POST' && u === '/SyncServlet') {
        return Promise.resolve(new Response(JSON.stringify({ jobId: 'job789' })));
      }
      if (u.includes('action=list')) return Promise.resolve(new Response('[]'));
      return Promise.resolve(new Response('{}'));
    });

    openPanel();
    render(<SyncPanel />);

    const input = screen.getByPlaceholderText(/youtube\.com\/playlist/i);
    await userEvent.type(input, 'https://youtube.com/playlist?list=xyz');
    await userEvent.click(screen.getByRole('button', { name: /^sync$/i }));

    const es = await waitFor(() => {
      const instance = FakeEventSource.latest();
      if (!instance) throw new Error('EventSource not yet constructed');
      return instance;
    });

    es.emitMessage('Downloading item 3 of 12');

    expect(await screen.findByText('3 / 12 tracks')).toBeInTheDocument();
    expect(screen.getByText('25%')).toBeInTheDocument();
  });

  it('disables the re-sync button for a playlist with no stored URL', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((url) => {
      const u = String(url);
      if (u.includes('action=list')) {
        return Promise.resolve(new Response(JSON.stringify([
          { name: 'No URL Playlist', url: '', lastSynced: '', tracks: 3 },
        ])));
      }
      return Promise.resolve(new Response('{}'));
    });

    openPanel();
    render(<SyncPanel />);

    await screen.findByText('No URL Playlist');
    const resyncButton = screen.getByTitle(/url not stored/i);
    expect(resyncButton).toBeDisabled();
  });
});
