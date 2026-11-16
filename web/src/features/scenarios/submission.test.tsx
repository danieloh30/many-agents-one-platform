import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, reply } from '../../test/fakes';
import { config, run, runList, scenarios, snapshot } from '../../test/fixtures';
import { renderApp, resetStores } from '../../test/render';

const TOKEN = 'presenter-test-token';

function baseRoutes(extra: Parameters<typeof mockFetch>[0] = {}) {
  return mockFetch({
    'GET /api/v1/config': () => config(),
    'GET /api/v1/scenarios': () => scenarios,
    'GET /api/v1/runs': () => runList(),
    'GET /api/v1/ops/snapshot': () => snapshot(),
    'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
    'GET /api/v1/session': (c) => (c.headers.authorization === `Bearer ${TOKEN}` ? reply(204) : reply(401, { code: 'UNAUTHORIZED' })),
    [`GET /api/v1/runs/${run().runId}`]: () => run({ state: 'SUBMITTED', recommendation: null, evidence: [] }),
    [`GET /api/v1/runs/${run().runId}/timeline`]: () => ({ items: [] }),
    'GET /api/v1/audit/invocations': () => ({ items: [] }),
    ...extra,
  });
}

describe('scenario submission', () => {
  it('sends POST /api/v1/runs with Idempotency-Key and Bearer token, and a double click sends one request', async () => {
    resetStores({ token: TOKEN });
    let release: () => void = () => undefined;
    const api = baseRoutes({
      'POST /api/v1/runs': () =>
        new Promise((resolve) => {
          release = () => resolve(reply(202, run({ state: 'SUBMITTED' })));
        }),
    });
    const user = userEvent.setup();
    renderApp('/');

    const button = await screen.findByRole('button', { name: 'Run scenario: Evidence-backed proposal' });
    await waitFor(() => expect(button).toBeEnabled());
    await user.dblClick(button);
    release();

    await waitFor(() => expect(api.count('POST', '/api/v1/runs')).toBe(1));
    const post = api.calls.find((c) => c.method === 'POST')!;
    expect(post.headers.authorization).toBe(`Bearer ${TOKEN}`);
    expect(post.headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(post.body).toMatchObject({ scenario: 'SUCCESS', remediationMode: 'PROPOSE_ONLY', faultInjection: 'NONE' });
    // Navigates to the new run.
    expect(await screen.findByLabelText(`Run ID: ${run().runId}`)).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(api.count('POST', '/api/v1/runs')).toBe(1);
  });

  it('reuses the same Idempotency-Key when the network fails and the request is retried', async () => {
    resetStores({ token: TOKEN });
    let attempts = 0;
    const api = baseRoutes({
      'POST /api/v1/runs': () => {
        attempts += 1;
        if (attempts === 1) throw new TypeError('Failed to fetch');
        return reply(202, run({ state: 'SUBMITTED' }));
      },
    });
    const user = userEvent.setup();
    renderApp('/');
    const button = await screen.findByRole('button', { name: 'Run scenario: Policy denial' });
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);
    await waitFor(() => expect(api.count('POST', '/api/v1/runs')).toBe(2), { timeout: 3000 });
    const keys = api.calls.filter((c) => c.method === 'POST').map((c) => c.headers['idempotency-key']);
    expect(keys[0]).toBeTruthy();
    expect(keys[0]).toBe(keys[1]);
  });

  it('keeps mutation controls visible but disabled with an explanation when no token is present', async () => {
    resetStores();
    const api = baseRoutes();
    renderApp('/');
    const button = await screen.findByRole('button', { name: 'Run scenario: Evidence-backed proposal' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(/Read-only: enter the presenter token/);
    expect(api.count('POST', '/api/v1/runs')).toBe(0);
  });

  it('validates the new-incident form with the server limits before sending', async () => {
    resetStores({ token: TOKEN });
    const api = baseRoutes({ 'POST /api/v1/runs': () => reply(202, run()) });
    const user = userEvent.setup();
    renderApp('/');
    await user.click(screen.getByText('New incident'));
    const submit = await screen.findByRole('button', { name: 'Submit incident' });
    await waitFor(() => expect(submit).toBeEnabled());
    await user.type(screen.getByLabelText('Title'), 'x'.repeat(121));
    await user.click(submit);
    expect(await screen.findByText('Title must be at most 120 characters.')).toBeInTheDocument();
    expect(screen.getByText('Description is required.')).toBeInTheDocument();
    expect(api.count('POST', '/api/v1/runs')).toBe(0);
  });
});
