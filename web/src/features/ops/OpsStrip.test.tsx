import { render, screen, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { Tooltip } from 'radix-ui';
import axe from 'axe-core';
import { queryKeys } from '../../api/client';
import { createQueryClient } from '../../app/App';
import { mockFetch } from '../../test/fakes';
import { config, snapshot } from '../../test/fixtures';
import { resetStores } from '../../test/render';
import type { OpsSnapshot } from '../../api/types';
import { OpsStrip } from './OpsStrip';

function renderStrip(snap: OpsSnapshot) {
  const qc = createQueryClient({ retry: false });
  qc.setQueryData(queryKeys.snapshot, snap);
  qc.setQueryData(queryKeys.config, config());
  qc.setQueryData(queryKeys.burst, { active: false, burstId: null, runs: null, completed: null, startedAt: null });
  return render(
    <QueryClientProvider client={qc}>
      <Tooltip.Provider>
        <MemoryRouter>
          <OpsStrip />
        </MemoryRouter>
      </Tooltip.Provider>
    </QueryClientProvider>,
  );
}

const nullSnapshot = () =>
  snapshot({
    queues: [
      { stream: 'MAOP_WORK', consumer: 'evidence-agents', status: 'UNAVAILABLE', pending: null, inFlight: null, redelivered: null, oldestMessageAgeSeconds: null },
    ],
    workloads: [
      {
        name: 'evidence-agent',
        status: 'UNAVAILABLE',
        desiredReplicas: null,
        readyReplicas: null,
        minReplicas: null,
        maxReplicas: null,
        scaler: null,
        pods: null,
      },
    ],
    platform: [{ component: 'prometheus', status: 'UNAVAILABLE', detail: 'connection refused' }],
  });

describe('operations strip', () => {
  beforeEach(() => {
    resetStores();
    mockFetch({});
  });

  it('renders null snapshot values as "Unavailable", never 0, and no green status', () => {
    renderStrip(nullSnapshot());
    const queueTile = screen.getByRole('region', { name: 'Evidence queue' });
    for (const label of ['Pending', 'In flight', 'Oldest age']) {
      const metric = within(queueTile).getByText(label).parentElement!;
      expect(metric).toHaveTextContent('Unavailable');
      expect(metric).not.toHaveTextContent(/\b0\b/);
    }
    const replicas = screen.getByRole('region', { name: 'Evidence agent replicas' });
    expect(within(replicas).getByText('Ready / desired').parentElement).toHaveTextContent('Unavailable');
    expect(within(replicas).getByText('Min – max').parentElement).toHaveTextContent('Unavailable');
    expect(within(replicas).getByText('Scaler: Unavailable')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Worker pods' })).getByText('Unavailable')).toBeInTheDocument();
    expect(screen.getByText('Prometheus: Unavailable')).toBeInTheDocument();
    expect(document.querySelector('[data-tone="ok"]')).toBeNull();
    expect(screen.queryByText('OK')).toBeNull();
  });

  it('greys out tiles and reads "Stale" when observedAt is older than 10 s', () => {
    renderStrip(snapshot({ observedAt: new Date(Date.now() - 14_000).toISOString() }));
    expect(screen.getByTestId('snapshot-age')).toHaveTextContent(/Stale 1[45] s/);
  });

  it('shows reported values and the Grafana link disabled with a reason when null', async () => {
    const { container } = renderStrip(snapshot());
    const queueTile = screen.getByRole('region', { name: 'Evidence queue' });
    expect(within(queueTile).getByText('Pending').parentElement).toHaveTextContent('3');
    expect(screen.getByText(/Grafana dashboard/).closest('[aria-disabled="true"]')).not.toBeNull();
    expect(screen.getAllByText(/since page load/i).length).toBeGreaterThan(0);
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => v.id)).toEqual([]);
  });
});
