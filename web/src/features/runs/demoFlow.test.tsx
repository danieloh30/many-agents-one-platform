import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, reply } from '../../test/fakes';
import { config, invocations, run, runList, RUN_ID, scenarios, snapshot, timeline } from '../../test/fixtures';
import { renderApp, resetStores } from '../../test/render';
import { useUi } from '../../app/uiStore';
import type { Run } from '../../api/types';

function routes(r: Run) {
  return mockFetch({
    'GET /api/v1/config': () => config(),
    'GET /api/v1/scenarios': () => scenarios,
    'GET /api/v1/runs': () => runList(r),
    'GET /api/v1/ops/snapshot': () => snapshot(),
    'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
    'GET /api/v1/session': () => reply(401),
    [`GET /api/v1/runs/${RUN_ID}`]: () => r,
    [`GET /api/v1/runs/${RUN_ID}/timeline`]: () => timeline,
    'GET /api/v1/audit/invocations': () => invocations,
  });
}

describe('the console follows the run on screen', () => {
  it('unfolds the selected run\'s scenario in the left rail', async () => {
    resetStores();
    routes(run({ scenario: 'POLICY_DENIAL' }));
    renderApp(`/runs/${RUN_ID}`);
    const list = await screen.findByRole('region', { name: 'Scenarios' });
    expect(await within(list).findByRole('button', { name: /^Policy denial$/, expanded: true })).toBeInTheDocument();
  });

  it('moves the presenter guide to the selected run\'s scenario and keeps Preflight and Reset in the top bar', async () => {
    resetStores();
    useUi.setState({ presenter: true });
    routes(run({ scenario: 'POLICY_DENIAL' }));
    renderApp(`/runs/${RUN_ID}`);
    const chip = await screen.findByTestId('guide-chip-POLICY_DENIAL');
    await waitFor(() => expect(chip).toHaveAttribute('aria-pressed', 'true'));
    const banner = screen.getByRole('banner');
    expect(within(banner).getByRole('button', { name: 'Preflight' })).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Guide' })).queryByRole('button', { name: 'Preflight' })).toBeNull();
  });
});

describe('timeline puts the decision before the detail', () => {
  it('shows the recommendation above tasks and evidence, with evidence folded to one line each', async () => {
    resetStores();
    useUi.setState({ view: 'timeline' });
    routes(run());
    renderApp(`/runs/${RUN_ID}`);
    const summary = await screen.findByTestId('rec-summary');
    const tasks = screen.getByRole('region', { name: 'Tasks and deliveries' });
    expect(summary.compareDocumentPosition(tasks) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    const cards = screen.getAllByTestId('evidence-card') as HTMLDetailsElement[];
    expect(cards.length).toBeGreaterThan(0);
    expect(cards.every((c) => !c.open)).toBe(true);
    await userEvent.click(within(cards[0]!).getByRole('heading', { level: 4 }));
    expect(cards[0]!.open).toBe(true);
    expect(screen.getByTestId('action-line')).toHaveTextContent('ROLLBACK_DEPLOYMENT');
  });
});
