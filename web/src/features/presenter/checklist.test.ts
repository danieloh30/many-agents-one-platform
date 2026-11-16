import { parseRunEvent, type LiveEvent } from '../../api/events';
import type { SnapshotPoint } from '../../api/liveStore';
import { checklist } from './checklist';

const ev = (type: string, data: Record<string, unknown>, runId = 'r2'): LiveEvent =>
  parseRunEvent(
    { specversion: '1.0', id: crypto.randomUUID(), source: '/maop/tool-service', type: `io.github.danieloh30.maop.${type}`, maoprunid: runId, data },
    null,
  )!;

describe('presenter checklist', () => {
  it('ticks the policy-denial items only after a DENIED tool event for the step run', () => {
    const base = { runId: 'r2', run: undefined, history: [], burstStartedAt: null };
    expect(checklist('POLICY_DENIAL', { ...base, events: [] }).every((i) => !i.done)).toBe(true);
    const executed = ev('tool.invocation.completed.v1', { invocationId: 'i0', toolName: 'deployment.get_history', status: 'EXECUTED' });
    expect(checklist('POLICY_DENIAL', { ...base, events: [executed] })[0]!.done).toBe(false);
    const otherRun = ev('tool.invocation.completed.v1', { invocationId: 'i1', toolName: 'x', status: 'DENIED' }, 'other');
    expect(checklist('POLICY_DENIAL', { ...base, events: [otherRun] })[0]!.done).toBe(false);
    const denied = ev('tool.invocation.completed.v1', {
      invocationId: 'i2',
      toolName: 'remediation.rollback_deployment',
      status: 'DENIED',
      decisionId: 'dec-9',
      reasons: ['approval_required'],
    });
    const items = checklist('POLICY_DENIAL', { ...base, events: [denied] });
    expect(items[0]).toMatchObject({ done: true, label: 'Tool call denied — decision dec-9' });
    expect(items[1]).toMatchObject({ done: true, label: 'Rule fired: approval_required' });
    expect(items[2]!.done).toBe(false);
  });

  it('ticks burst scaling only when desiredReplicas exceeds minReplicas after the burst started', () => {
    const p = (t: number, pending: number | null, desired: number | null): SnapshotPoint => ({ t, pending, inFlight: 0, desired, ready: desired, min: 1 });
    const ctx = (history: SnapshotPoint[]) => ({ events: [], runId: null, run: undefined, history, burstStartedAt: 10_000 });
    expect(checklist('BURST', ctx([p(5_000, 5, 3)]))[1]!.done).toBe(false); // before the burst
    expect(checklist('BURST', ctx([p(11_000, null, null)])).some((i) => i.done)).toBe(false); // unavailable ≠ 0
    const items = checklist('BURST', ctx([p(11_000, 8, 1), p(13_000, 6, 3), p(20_000, 0, 3), p(40_000, 0, 1)]));
    expect(items.map((i) => i.done)).toEqual([true, true, true, true]);
  });
});
