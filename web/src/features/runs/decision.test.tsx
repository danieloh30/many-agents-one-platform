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

describe('decision needed', () => {
  it('shows the decision bar on the topology and jumps to Approve / Reject on the timeline', async () => {
    resetStores();
    routes(run({ approvalDeadline: new Date(Date.now() + 8 * 60_000).toISOString() }));
    renderApp(`/runs/${RUN_ID}`);
    const bars = await screen.findAllByTestId('decision-bar');
    expect(bars[0]).toHaveTextContent('Your decision is needed');
    expect(bars[0]).toHaveTextContent(/left to approve or reject/);

    await userEvent.click(within(bars[0]!).getByRole('button', { name: 'Review & decide' }));
    expect(useUi.getState().view).toBe('timeline');
    expect(await screen.findByRole('button', { name: /Approve \(simulate\)/ })).toBeInTheDocument();
    expect(document.getElementById('run-decision')).not.toBeNull();
  });

  it('shows no decision bar once the run has finished', async () => {
    resetStores();
    routes(run({ state: 'COMPLETED', outcome: 'ACTION_REJECTED' }));
    renderApp(`/runs/${RUN_ID}`);
    const inspector = await screen.findByRole('complementary', { name: 'Inspector' });
    await within(inspector).findByText('Run ID');
    expect(screen.queryByTestId('decision-bar')).toBeNull();
  });

  it('opens the inspector on Policy for a denial run when the timeline is shown', async () => {
    resetStores();
    useUi.setState({ view: 'timeline' });
    routes(run({ scenario: 'POLICY_DENIAL' }));
    renderApp(`/runs/${RUN_ID}`);
    await waitFor(() => expect(useUi.getState().inspectorTab).toBe('policy'));
  });

  it('explains on a policy-denial run why it differs from a normal run, and says to approve', async () => {
    resetStores();
    useUi.setState({ view: 'timeline' });
    routes(
      run({
        remediationMode: 'ATTEMPT_UNAPPROVED',
        executionAttempt: { invocationId: 'inv-9', status: 'DENIED', decisionId: 'd-1', reasons: ['approval_required'] },
      }),
    );
    renderApp(`/runs/${RUN_ID}`);
    const note = await screen.findByTestId('unapproved-note');
    expect(note).toHaveTextContent('ATTEMPT_UNAPPROVED');
    expect(note).toHaveTextContent(/Approve below/);
  });

  it('shows no such note on a normal run', async () => {
    resetStores();
    useUi.setState({ view: 'timeline' });
    routes(run());
    renderApp(`/runs/${RUN_ID}`);
    await screen.findByRole('button', { name: /Approve \(simulate\)/ });
    expect(screen.queryByTestId('unapproved-note')).toBeNull();
  });
});
