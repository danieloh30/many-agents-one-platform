import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, reply } from '../test/fakes';
import { config, runList, scenarios, snapshot } from '../test/fixtures';
import { renderApp, resetStores } from '../test/render';
import type { ConsoleConfig } from '../api/types';
import { useUi, THEME_KEY } from './uiStore';

function routes(cfg: ConsoleConfig) {
  return mockFetch({
    'GET /api/v1/config': () => cfg,
    'GET /api/v1/scenarios': () => scenarios,
    'GET /api/v1/runs': () => runList(),
    'GET /api/v1/ops/snapshot': () => snapshot(),
    'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
    'GET /api/v1/session': () => reply(401),
  });
}

describe('top bar', () => {
  it('renders the deterministic model badge distinctly (amber warn tone, explicit label)', async () => {
    resetStores();
    routes(config({ model: { mode: 'DETERMINISTIC', name: null } }));
    renderApp('/');
    const badge = await screen.findByTestId('model-badge');
    await waitFor(() => expect(badge).toHaveTextContent(/deterministic/i));
    expect(badge).toHaveAttribute('data-tone', 'warn');
    expect(badge.className).toContain('text-warn');
    expect(badge.className).toContain('border-2');
    expect(screen.getByTestId('synthetic-badge')).toHaveTextContent('SYNTHETIC DATA');
  });

  it('renders the live model badge with the model name and a different tone', async () => {
    resetStores();
    routes(config());
    renderApp('/');
    await waitFor(() => expect(screen.getByTestId('model-badge')).toHaveTextContent('LIVE gpt-4o-mini'));
    expect(screen.getByTestId('model-badge')).toHaveAttribute('data-tone', 'purple');
  });

  it('follows the system theme until chosen, then persists the choice in localStorage', async () => {
    resetStores();
    routes(config());
    const user = userEvent.setup();
    const first = renderApp('/');
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(screen.getByTestId('theme-light')).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByTestId('theme-dark'));
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(screen.getByTestId('theme-dark')).toHaveAttribute('aria-pressed', 'true');
    expect(localStorage.getItem(THEME_KEY)).toBe('dark');
    await user.click(screen.getByTestId('theme-light'));
    expect(document.documentElement.dataset.theme).toBe('light');
    await user.click(screen.getByTestId('theme-dark'));
    first.unmount();

    // A fresh page load reads the stored choice.
    const stored = localStorage.getItem(THEME_KEY);
    useUi.setState({ themeChoice: null });
    vi.resetModules();
    const { useUi: freshUi } = await import('./uiStore');
    expect(stored).toBe('dark');
    expect(freshUi.getState().themeChoice).toBe('dark');
  });

  it('never shows date text in the product chrome', async () => {
    resetStores();
    routes(config());
    renderApp('/');
    await screen.findByTestId('model-badge');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Agent Operations Console');
    expect(document.body.textContent ?? '').not.toMatch(/\b20\d\d\b/);
  });

  it('offers Reset in the top bar and clears demo state after confirmation', async () => {
    resetStores();
    const api = mockFetch({
      'GET /api/v1/config': () => config({ auth: { presenterTokenRequired: false } } as Partial<ConsoleConfig>),
      'GET /api/v1/scenarios': () => scenarios,
      'GET /api/v1/runs': () => runList(),
      'GET /api/v1/ops/snapshot': () => snapshot(),
      'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
      'GET /api/v1/session': () => reply(401),
      'POST /api/v1/admin/reset': () => reply(204),
    });
    renderApp('/');
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Reset' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent('Reset demonstration state?');
    await user.click(within(dialog).getByRole('button', { name: 'Reset' }));
    await waitFor(() => expect(api.count('POST', '/api/v1/admin/reset')).toBe(1));
  });
});
