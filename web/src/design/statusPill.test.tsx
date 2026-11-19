import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { opsSnapshotSchema } from '../api/events';
import { preflightIssues } from '../features/presenter/PreflightPanel';
import { snapshot } from '../test/fixtures';
import { StatusPill } from './ui';

describe('workload SCALING status', () => {
  it('reads "Scaling up", not Degraded', () => {
    render(<StatusPill status="SCALING" />);
    expect(screen.getByText('Scaling up')).toBeInTheDocument();
    expect(screen.queryByText(/Degraded/)).toBeNull();
  });

  it('survives SSE validation and is not a preflight issue', () => {
    const snap = snapshot();
    const wl = snap.workloads.map((w) => (w.name === 'evidence-agent' ? { ...w, status: 'SCALING' as const } : w));
    const parsed = opsSnapshotSchema.parse({ ...snap, workloads: wl });
    expect(parsed.workloads.find((w) => w.name === 'evidence-agent')?.status).toBe('SCALING');
    const issues = preflightIssues({ ...snap, workloads: wl }, undefined, 'open');
    expect(issues.some((i) => i.name === 'Workload evidence-agent')).toBe(false);
  });
});
