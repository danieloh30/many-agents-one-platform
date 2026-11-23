import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, reply } from '../../test/fakes';
import { config, run, runList, scenarios, snapshot } from '../../test/fixtures';
import { renderApp, resetStores } from '../../test/render';
import { useUi } from '../../app/uiStore';

function routes() {
  return mockFetch({
    'GET /api/v1/config': () => config(),
    'GET /api/v1/scenarios': () => scenarios,
    'GET /api/v1/runs': () => runList(run()),
    'GET /api/v1/ops/snapshot': () => snapshot(),
    'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
    'GET /api/v1/session': () => reply(401),
  });
}

describe('presenter guide', () => {
  beforeEach(() => {
    resetStores();
    useUi.setState({ presenter: true });
    routes();
  });

  it('shows one step in full, with the others as chips that switch it', async () => {
    renderApp('/');
    const chips = await screen.findByRole('group', { name: 'Guide steps' });
    expect(within(chips).getAllByRole('button')).toHaveLength(5);
    expect(screen.getAllByTestId('guide-step')).toHaveLength(1);
    expect(screen.getByTestId('guide-step')).toHaveTextContent('Success');

    await userEvent.click(screen.getByTestId('guide-chip-POLICY_DENIAL'));
    expect(screen.getByTestId('guide-step')).toHaveTextContent('Policy denial');
    expect(screen.getByTestId('guide-chip-POLICY_DENIAL')).toHaveAttribute('aria-pressed', 'true');
  });

  it('folds to the chip line with G and the fold button', async () => {
    renderApp('/');
    await screen.findByTestId('guide-step');
    await userEvent.keyboard('g');
    expect(screen.queryByTestId('guide-step')).not.toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Unfold guide (G)' }));
    expect(screen.getByTestId('guide-step')).toBeVisible();
  });

  it('points the empty inspector at the guide, not the hidden Recent runs', async () => {
    renderApp('/');
    const empty = await screen.findByTestId('inspector-empty');
    expect(empty).toHaveTextContent(/Press Run in the guide above/);
    expect(empty).not.toHaveTextContent(/Recent runs/);
  });
});
