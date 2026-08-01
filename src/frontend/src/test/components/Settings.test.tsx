import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Settings } from '../../components/Settings';
import { useStore, DEFAULT_SETTINGS } from '../../store';

function openPanel() {
  useStore.getState().setSettingsOpen(true);
}

beforeEach(() => {
  useStore.setState({ settingsOpen: false, settings: DEFAULT_SETTINGS });
  vi.restoreAllMocks();
  document.documentElement.style.cssText = '';
});

describe('Settings', () => {
  it('renders nothing when closed', () => {
    render(<Settings />);
    expect(screen.queryByText('Settings')).not.toBeInTheDocument();
  });

  /**
   * The core hydration regression: opening the panel must populate form
   * fields from the SERVER's persisted values, not leave them at whatever
   * the Zustand store's hardcoded defaults are. This is the same bug class
   * fixed earlier in App.tsx (settings appearing to "reset" on relaunch),
   * checked here specifically for Settings.tsx's own local form state.
   */
  it('populates form fields from fetchSettings() when opened, not just the hardcoded defaults', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      ...DEFAULT_SETTINGS,
      'mpd.host':   '192.168.1.50',
      'mpd.port':   '6601',
      'music.dir':  '/mnt/music',
    })));

    openPanel();
    render(<Settings />);

    const hostInput = await screen.findByPlaceholderText('localhost') as HTMLInputElement;
    await waitFor(() => expect(hostInput.value).toBe('192.168.1.50'));

    const portInput = screen.getByPlaceholderText('6600') as HTMLInputElement;
    expect(portInput.value).toBe('6601');

    const dirInput = screen.getByPlaceholderText('~/Music') as HTMLInputElement;
    expect(dirInput.value).toBe('/mnt/music');
  });

  it('toggling "Pause on close" persists the correct boolean string', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response(JSON.stringify(DEFAULT_SETTINGS)));
    const saveSpy = vi.spyOn(global, 'fetch');

    openPanel();
    render(<Settings />);
    await screen.findByText('Pause on close');

    const toggle = screen.getByText('Pause on close').closest('.settings-row')!
      .querySelector('.toggle-switch')!;
    await userEvent.click(toggle);

    await waitFor(() => {
      const saveCall = saveSpy.mock.calls.find(([url, init]) =>
        url === '/ConfigServlet' && (init as RequestInit)?.method === 'POST'
      );
      expect(saveCall).toBeDefined();
      const body = JSON.parse((saveCall![1] as RequestInit).body as string);
      expect(body['player.pauseOnClose']).toBe('true');
    });
  });

  it('changing the accent color applies the CSS custom properties to the document root', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response(JSON.stringify(DEFAULT_SETTINGS)));

    openPanel();
    render(<Settings />);
    await screen.findByText('Accent colour');

    const colorInput = document.querySelector('input[type="color"]') as HTMLInputElement;
    const { fireEvent } = await import('@testing-library/react');
    fireEvent.change(colorInput, { target: { value: '#ff0000' } });

    await waitFor(() => {
      expect(document.documentElement.style.getPropertyValue('--accent')).toBe('#ff0000');
    });
  });

  /**
   * The bg-opacity slider is deliberately reversed (slider value =
   * 60 - opacity*100) so that a higher slider position means MORE blur —
   * this inverted formula is exactly the kind of thing worth pinning down
   * with a test, since it reads as backwards at a glance.
   */
  it('computes the correct (reversed) opacity value from the background slider position', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response(JSON.stringify(DEFAULT_SETTINGS)));
    const saveSpy = vi.spyOn(global, 'fetch');

    openPanel();
    render(<Settings />);
    await screen.findByText('Background opacity');

    const { fireEvent } = await import('@testing-library/react');
    const sliders = screen.getAllByRole('slider');
    // Found by the containing field's text content, not by position — this
    // stays correct even if the Vinyl speed / Background opacity fields are
    // ever reordered in the JSX.
    const bgSlider = sliders.find(s => s.closest('.settings-field')?.textContent?.includes('Background opacity'))!;

    fireEvent.change(bgSlider, { target: { value: '20' } });

    await waitFor(() => {
      const saveCall = saveSpy.mock.calls.find(([url, init]) => {
        if (url !== '/ConfigServlet' || (init as RequestInit)?.method !== 'POST') return false;
        const body = JSON.parse((init as RequestInit).body as string);
        return 'ui.bgOpacity' in body;
      });
      expect(saveCall).toBeDefined();
      const body = JSON.parse((saveCall![1] as RequestInit).body as string);
      // slider=20 → opacity = (60-20)/100 = 0.40
      expect(body['ui.bgOpacity']).toBe('0.40');
    });
  });

  it('clicking a visualizer mode button persists that mode and marks it active', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response(JSON.stringify(DEFAULT_SETTINGS)));
    const saveSpy = vi.spyOn(global, 'fetch');

    openPanel();
    render(<Settings />);
    const barButton = await screen.findByRole('button', { name: /bar/i });

    await userEvent.click(barButton);

    expect(barButton.className).toContain('active');
    await waitFor(() => {
      const saveCall = saveSpy.mock.calls.find(([url, init]) => {
        if (url !== '/ConfigServlet' || (init as RequestInit)?.method !== 'POST') return false;
        const body = JSON.parse((init as RequestInit).body as string);
        return body['visualizer.mode'] === 'bar';
      });
      expect(saveCall).toBeDefined();
    });
  });
});
