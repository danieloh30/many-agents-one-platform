import { act, screen, within } from '@testing-library/react';
import axe from 'axe-core';
import { FakeEventSource, mockFetch, reply } from '../../test/fakes';
import { config, deniedInvocations, DENIED_RUN_ID, run, runList, scenarios, snapshot, SPIFFE_REC } from '../../test/fixtures';
import { renderApp, resetStores } from '../../test/render';
import { useNotify } from '../../app/notify';
import { useUi } from '../../app/uiStore';

function routes() {
  const r = run({
    runId: DENIED_RUN_ID,
    scenario: 'POLICY_DENIAL',
    remediationMode: 'ATTEMPT_UNAPPROVED',
    executionAttempt: { invocationId: 'inv-denied', status: 'DENIED', decisionId: 'dec-deny-42', reasons: ['approval_required'] },
  });
  return mockFetch({
    'GET /api/v1/config': () => config(),
    'GET /api/v1/scenarios': () => scenarios,
    'GET /api/v1/runs': () => runList(r),
    'GET /api/v1/ops/snapshot': () => snapshot(),
    'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
    'GET /api/v1/session': () => reply(401),
    [`GET /api/v1/runs/${DENIED_RUN_ID}`]: () => r,
    [`GET /api/v1/runs/${DENIED_RUN_ID}/timeline`]: () => ({ items: [] }),
    'GET /api/v1/audit/invocations': () => deniedInvocations,
    'GET /api/v1/audit/invocations/inv-denied': () => deniedInvocations.items[0],
  });
}

describe('denied remediation presentation', () => {
  it('shows decision DENIED, reason approval_required, the verified caller, and no side effect', async () => {
    resetStores();
    useUi.setState({ inspectorTab: 'policy' });
    routes();
    renderApp(`/runs/${DENIED_RUN_ID}`);

    const inspector = await screen.findByRole('complementary', { name: 'Inspector' });
    const item = await within(inspector).findByText('remediation.rollback_deployment');
    const card = item.closest('li')!;
    expect(within(card).getByTestId('decision-pill')).toHaveTextContent('DENIED');
    expect(within(card).getByText('approval_required')).toBeInTheDocument();
    expect(within(card).getByTestId('caller-spiffe-id')).toHaveTextContent(SPIFFE_REC);
    expect(within(card).getByText('Verified by mTLS')).toBeInTheDocument();
    expect(within(card).getByText(/No side effect — denied before execution/)).toBeInTheDocument();
  });

  it('opens the deep-linked invocation in the Tool audit tab with policy and idempotency details', async () => {
    resetStores();
    routes();
    const { container } = renderApp(`/runs/${DENIED_RUN_ID}/audit/inv-denied`);
    const detail = await screen.findByRole('article', { name: 'Tool invocation remediation.rollback_deployment' });
    expect(within(detail).getByTestId('decision-pill')).toHaveTextContent('DENIED');
    expect(within(detail).getByTestId('policy-reason')).toHaveTextContent('approval_required');
    expect(within(detail).getByTestId('caller-spiffe-id')).toHaveTextContent(SPIFFE_REC);
    expect(within(detail).getByText('deny')).toBeInTheDocument();
    expect(within(detail).getByText(/No side effect — denied before execution/)).toBeInTheDocument();
    expect(within(detail).getByText('SIMULATED')).toBeInTheDocument();

    // axe (jsdom cannot compute colour; contrast is covered by contrast.test.ts).
    const results = await axe.run(detail, { rules: { 'color-contrast': { enabled: false } } });
    const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(serious.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
    expect(container).toBeTruthy();
  });

  it('raises a non-blocking policy toast for a live DENIED tool event', async () => {
    resetStores();
    routes();
    renderApp(`/runs/${DENIED_RUN_ID}`);
    await screen.findByRole('complementary', { name: 'Inspector' });
    const es = FakeEventSource.latest();
    act(() => {
      es.open();
      es.emit(
        'run-event',
        {
          specversion: '1.0',
          id: 'evt-1',
          source: '/maop/tool-service',
          type: 'io.github.danieloh30.maop.tool.invocation.completed.v1',
          subject: `runs/${DENIED_RUN_ID}`,
          maoprunid: DENIED_RUN_ID,
          data: {
            invocationId: 'inv-denied',
            toolName: 'remediation.rollback_deployment',
            toolClass: 'REMEDIATION',
            status: 'DENIED',
            callerSpiffeId: SPIFFE_REC,
            decisionId: 'dec-deny-42',
            simulated: true,
            runId: DENIED_RUN_ID,
            reasons: ['approval_required'],
          },
        },
        '41',
      );
    });
    expect(useNotify.getState().toasts.at(-1)).toMatchObject({ tone: 'policy', title: 'Policy denied remediation.rollback_deployment' });
    expect(await screen.findByText('Policy denied remediation.rollback_deployment')).toBeInTheDocument();
    expect(screen.getByTestId('live-region')).toHaveTextContent('Policy denied remediation.rollback_deployment: approval_required');
  });
});
