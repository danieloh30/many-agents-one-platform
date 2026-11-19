import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, reply } from '../../test/fakes';
import { config, run, runList, scenarios, snapshot } from '../../test/fixtures';
import { renderApp, resetStores } from '../../test/render';
import { groupRuns } from './RecentRuns';

function routes(runs = runList()) {
  return mockFetch({
    'GET /api/v1/config': () => config(),
    'GET /api/v1/scenarios': () => scenarios,
    'GET /api/v1/runs': () => runs,
    'GET /api/v1/ops/snapshot': () => snapshot(),
    'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
    'GET /api/v1/session': () => reply(401),
  });
}

const burstRun = (n: number, state: 'COMPLETED' | 'GATHERING_EVIDENCE') =>
  run({
    runId: `0197a6f2-9000-7b3a-9d2e-00000000000${n}`,
    burstId: 'burst-1',
    scenario: 'BURST',
    state,
    incident: { title: `Load run ${n}/3: checkout errors`, description: 'x', service: 'checkout' },
  });

describe('left rail and inspector layout', () => {
  it('shows scenarios as one-line rows, one expanded at a time, with how each run ends', async () => {
    resetStores();
    routes();
    renderApp('/');
    const list = await screen.findByRole('region', { name: 'Scenarios' });
    const toggles = await within(list).findAllByRole('button', { expanded: false });
    const first = await within(list).findByRole('button', { name: /Evidence-backed proposal/, expanded: true });
    expect(within(list).getByText('End it with Approve on the Timeline tab.')).toBeInTheDocument();
    expect(toggles.length).toBeGreaterThanOrEqual(3);

    await userEvent.click(within(list).getByRole('button', { name: /^Policy denial$/ }));
    expect(first).toHaveAttribute('aria-expanded', 'false');
    expect(within(list).getByText('End it with Reject: the agent tried to act without approval.')).toBeInTheDocument();
    expect(within(list).queryByText('End it with Approve on the Timeline tab.')).toBeNull();
    // Every scenario keeps its Run button while folded.
    expect(within(list).getAllByRole('button', { name: /^Run scenario:/ })).toHaveLength(4);
  });

  it('folds a burst into one Recent runs entry that expands to its runs', async () => {
    resetStores();
    routes(runList(burstRun(1, 'COMPLETED'), burstRun(2, 'COMPLETED'), burstRun(3, 'GATHERING_EVIDENCE'), run()));
    renderApp('/');
    const group = await screen.findByTestId('burst-group');
    expect(group).toHaveTextContent('Burst · 3 runs');
    expect(group).toHaveTextContent('2 of 3 finished');
    expect(screen.queryByText('Load run 1/3: checkout errors')).toBeNull();
    await userEvent.click(group);
    expect(screen.getByText('Load run 1/3: checkout errors')).toBeInTheDocument();
    expect(screen.getByText('Checkout error rate elevated after release')).toBeInTheDocument();
  });

  it('keeps list order and places a burst where its first run appears', () => {
    const items = runList(run({ runId: 'a' }), burstRun(1, 'COMPLETED'), run({ runId: 'b' }), burstRun(2, 'COMPLETED')).items;
    expect(groupRuns(items).map((e) => (e.kind === 'run' ? e.run.runId : `burst:${e.runs.length}`))).toEqual(['a', 'burst:2', 'b']);
  });

  it('tells the viewer what to do when no run is selected', async () => {
    resetStores();
    routes();
    renderApp('/');
    const empty = await screen.findByTestId('inspector-empty');
    expect(await within(empty).findByText(/No runs yet/)).toBeInTheDocument();
    expect(within(empty).getByText('Tool audit')).toBeInTheDocument();
  });
});
