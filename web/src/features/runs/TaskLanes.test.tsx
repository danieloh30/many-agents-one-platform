import { screen, within } from '@testing-library/react';
import { mockFetch, reply } from '../../test/fakes';
import { config, invocations, run, runList, RUN_ID, scenarios, snapshot } from '../../test/fixtures';
import { renderApp, resetStores } from '../../test/render';
import type { RunTimelineEntry } from '../../api/types';
import { useUi } from '../../app/uiStore';

const started = (seq: number, taskId: string, delivery: number, at: string): RunTimelineEntry => ({
  seq,
  at,
  kind: 'TASK_STARTED',
  from: null,
  to: null,
  taskId,
  message: null,
  data: { delivery },
});

function routes() {
  const r = run({
    scenario: 'REDELIVERY',
    faultInjection: 'DROP_ACK_ONCE',
    tasks: [
      { taskId: 'task-1', kind: 'EVIDENCE', topic: 'DEPLOYMENT_HISTORY', state: 'COMPLETED', deliveries: 2, startedAt: null, finishedAt: null },
      { taskId: 'task-2', kind: 'EVIDENCE', topic: 'ERROR_SUMMARY', state: 'COMPLETED', deliveries: 1, startedAt: null, finishedAt: null },
    ],
  });
  const inv = { ...invocations.items[0], requests: 2, executions: 1 };
  const other = { ...invocations.items[0], invocationId: 'inv-2', taskId: 'task-2', toolName: 'errors.summarize' };
  return mockFetch({
    'GET /api/v1/config': () => config(),
    'GET /api/v1/scenarios': () => scenarios,
    'GET /api/v1/runs': () => runList(r),
    'GET /api/v1/ops/snapshot': () => snapshot(),
    'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
    'GET /api/v1/session': () => reply(401),
    [`GET /api/v1/runs/${RUN_ID}`]: () => r,
    [`GET /api/v1/runs/${RUN_ID}/timeline`]: () => ({
      items: [
        started(1, 'task-1', 1, '2026-01-01T14:10:05Z'),
        started(2, 'task-2', 1, '2026-01-01T14:10:05Z'),
        started(3, 'task-1', 2, '2026-01-01T14:10:26Z'),
      ],
    }),
    'GET /api/v1/audit/invocations': () => ({ items: [inv, other] }),
  });
}

describe('task lanes on a redelivery run', () => {
  it('marks only the redelivered task and shows that its tool call executed once', async () => {
    resetStores();
    useUi.setState({ view: 'timeline' });
    routes();
    renderApp(`/runs/${RUN_ID}`);

    const lanes = await screen.findByRole('list', { name: 'Task lanes' });
    const rows = within(lanes).getAllByRole('listitem').filter((li) => li.parentElement === lanes);
    expect(rows).toHaveLength(2);

    const redelivered = rows[0]!;
    const normal = rows[1]!;
    expect(within(redelivered).getByText('delivery 2')).toBeInTheDocument();
    expect(within(redelivered).getByTestId('redelivered-pill')).toHaveTextContent('Redelivered');
    const note = await within(redelivered).findByRole('list', { name: 'Repeated tool calls' });
    expect(note).toHaveTextContent('deployment.get_history: requested 2 times, executed once');
    expect(within(note).getByRole('link', { name: 'Open in Tool audit' })).toHaveAttribute(
      'href',
      `/runs/${RUN_ID}/audit/inv-1`,
    );

    expect(within(normal).queryByTestId('redelivered-pill')).toBeNull();
    expect(within(normal).queryByRole('list', { name: 'Repeated tool calls' })).toBeNull();
    expect(screen.queryByText('Fault injected')).toBeNull();
  });
});
