import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FakeEventSource, mockFetch, reply } from '../test/fakes';
import { config, invocations, RUN_ID, run, runList, scenarios, snapshot, timeline } from '../test/fixtures';
import { renderApp, resetStores } from '../test/render';
import { useUi } from './uiStore';

function routes() {
  const r = run();
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

describe('stream reconnect and REST recovery', () => {
  it('shows Reconnecting on error and refetches run state from REST on reopen, without any POST', async () => {
    resetStores();
    useUi.setState({ view: 'timeline' });
    const api = routes();
    renderApp(`/runs/${RUN_ID}`);

    expect(await screen.findByTestId('run-title')).toHaveTextContent('Checkout error rate elevated after release');
    const es = FakeEventSource.latest();
    expect(es.url).toBe('/api/v1/stream');
    act(() => es.open());
    expect(screen.getByTestId('stream-status')).toHaveTextContent('Live');

    const before = api.count('GET', `/api/v1/runs/${RUN_ID}`);
    act(() => es.error());
    expect(screen.getByTestId('stream-status')).toHaveTextContent('Reconnecting');

    act(() => es.open());
    expect(screen.getByTestId('stream-status')).toHaveTextContent('Live');
    await waitFor(() => expect(api.count('GET', `/api/v1/runs/${RUN_ID}`)).toBeGreaterThan(before));
    expect(screen.getByTestId('run-title')).toHaveTextContent('Checkout error rate elevated after release');
    expect(api.calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('reads Offline after repeated failures and refetches on a reset event', async () => {
    resetStores();
    const api = routes();
    renderApp(`/runs/${RUN_ID}`);
    await screen.findByLabelText(`Run ID: ${RUN_ID}`);
    const es = FakeEventSource.latest();
    act(() => {
      es.error();
      es.error();
      es.error();
    });
    expect(screen.getByTestId('stream-status')).toHaveTextContent('Offline — showing last loaded data');
    act(() => es.open());
    const before = api.count('GET', '/api/v1/runs');
    act(() => es.emit('reset', ''));
    await waitFor(() => expect(api.count('GET', '/api/v1/runs')).toBeGreaterThan(before));
    expect(api.calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('restores the selected run from the URL after a page refresh, from REST only', async () => {
    resetStores();
    useUi.setState({ view: 'timeline' });
    routes();
    const first = renderApp(`/runs/${RUN_ID}`);
    await screen.findByTestId('run-title');
    first.unmount();

    // "Refresh": new query cache, new stream, same URL.
    resetStores();
    useUi.setState({ view: 'timeline' });
    const api = routes();
    renderApp(`/runs/${RUN_ID}`);
    expect(await screen.findByTestId('run-title')).toHaveTextContent('Checkout error rate elevated after release');
    expect(await screen.findByText('Roll back checkout to v2.13.2.')).toBeInTheDocument();
    expect(api.count('GET', `/api/v1/runs/${RUN_ID}/timeline`)).toBeGreaterThan(0);
    expect(api.calls.filter((c) => c.method === 'POST')).toHaveLength(0);

    // The inspector audit tab is also rebuilt from REST.
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Tool audit' }));
    expect(await screen.findByRole('button', { name: 'deployment.get_history' })).toBeInTheDocument();
  });
});
