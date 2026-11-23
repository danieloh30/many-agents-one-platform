import type { LiveEvent } from '../../api/events';
import type { SnapshotPoint } from '../../api/liveStore';
import type { Run } from '../../api/types';
import type { TopologyNodeId } from '../../app/uiStore';

export interface CheckItem {
  id: string;
  label: string;
  done: boolean;
}

export interface GuideStep {
  id: 'SUCCESS' | 'POLICY_DENIAL' | 'BURST' | 'REDELIVERY' | 'SANDBOX';
  number: number;
  title: string;
  optional: boolean;
  focus: TopologyNodeId;
}

export const GUIDE_STEPS: GuideStep[] = [
  { id: 'SUCCESS', number: 1, title: 'Success', optional: false, focus: 'triage' },
  { id: 'POLICY_DENIAL', number: 2, title: 'Policy denial', optional: false, focus: 'tool-service' },
  { id: 'BURST', number: 3, title: 'Burst', optional: false, focus: 'evidence-agents' },
  { id: 'REDELIVERY', number: 4, title: 'Redelivery', optional: true, focus: 'evidence-agents' },
  { id: 'SANDBOX', number: 5, title: 'Sandbox', optional: true, focus: 'tool-service' },
];

export interface ChecklistContext {
  events: LiveEvent[];
  /** Run started for this step from this browser (null if none yet). */
  runId: string | null;
  run: Run | undefined;
  history: SnapshotPoint[];
  burstStartedAt: number | null;
}

const forRun = (events: LiveEvent[], runId: string | null) => (runId ? events.filter((e) => e.runId === runId) : []);

function reachedState(ctx: ChecklistContext, state: string): boolean {
  if (ctx.run?.runId === ctx.runId && ctx.run?.state === state) return true;
  return forRun(ctx.events, ctx.runId).some((e) => e.type === 'run.state_changed.v1' && e.data.to === state);
}

/**
 * Expected observations per guide step. An item is done only when a matching real event (or a REST
 * value reported by the backend) has been seen — nothing here simulates an outcome.
 */
export function checklist(step: GuideStep['id'], ctx: ChecklistContext): CheckItem[] {
  const evs = forRun(ctx.events, ctx.runId);
  switch (step) {
    case 'SUCCESS':
      return [
        { id: 'accepted', label: 'Run accepted by the console API', done: ctx.runId !== null },
        {
          id: 'evidence',
          label: 'Evidence collected by parallel workers',
          done: evs.some((e) => e.type === 'task.evidence.completed.v1') || (ctx.run?.runId === ctx.runId && (ctx.run?.evidence.length ?? 0) > 0),
        },
        {
          id: 'allowed',
          label: 'Read tool calls allowed by policy over mTLS',
          done: evs.some((e) => e.type === 'tool.invocation.completed.v1' && e.data.status === 'EXECUTED'),
        },
        { id: 'awaiting', label: 'Evidence-backed proposal awaiting approval', done: reachedState(ctx, 'AWAITING_APPROVAL') },
      ];
    case 'POLICY_DENIAL': {
      const denied = evs.find((e) => e.type === 'tool.invocation.completed.v1' && e.data.status === 'DENIED');
      const d = denied?.type === 'tool.invocation.completed.v1' ? denied.data : null;
      return [
        {
          id: 'denied',
          label: d ? `Tool call denied — decision ${d.decisionId ?? 'id not reported'}` : 'Tool call denied by policy',
          done: !!d,
        },
        {
          id: 'reason',
          label: d?.reasons?.length ? `Rule fired: ${d.reasons.join(', ')}` : 'Rule that fired is shown',
          done: !!d?.reasons?.length,
        },
        { id: 'fallback', label: 'Run falls back to awaiting approval', done: !!d && reachedState(ctx, 'AWAITING_APPROVAL') },
      ];
    }
    case 'BURST': {
      const pts = ctx.burstStartedAt === null ? [] : ctx.history.filter((p) => p.t >= ctx.burstStartedAt! - 1000);
      const riseIdx = pts.findIndex((p) => p.pending !== null && p.pending > 0);
      const scaleIdx = pts.findIndex((p) => p.desired !== null && p.min !== null && p.desired > p.min);
      const drained = riseIdx >= 0 && pts.slice(riseIdx + 1).some((p) => p.pending === 0);
      const back = scaleIdx >= 0 && pts.slice(scaleIdx + 1).some((p) => p.desired !== null && p.min !== null && p.desired === p.min);
      return [
        { id: 'depth', label: 'Queue depth rises', done: riseIdx >= 0 },
        { id: 'scaled', label: 'Evidence agents scaled above minimum', done: scaleIdx >= 0 },
        { id: 'drained', label: 'Queue drained', done: drained },
        { id: 'back', label: 'Replicas back at minimum', done: back },
      ];
    }
    case 'REDELIVERY':
      return [
        {
          id: 'second',
          label: 'Second delivery of one task observed',
          done:
            evs.some((e) => e.type === 'task.started.v1' && (e.data.delivery ?? 0) >= 2) ||
            (ctx.run?.runId === ctx.runId && !!ctx.run?.tasks.some((t) => (t.deliveries ?? 0) >= 2)),
        },
        {
          id: 'replayed',
          label: 'Tool call replayed — executed once',
          done: evs.some((e) => e.type === 'tool.invocation.completed.v1' && e.data.status === 'REPLAYED'),
        },
      ];
    case 'SANDBOX': {
      const sandboxed = evs.find(
        (e) => e.type === 'tool.invocation.completed.v1' && e.data.toolClass === 'SANDBOXED' && e.data.status === 'EXECUTED',
      );
      const analysis = ctx.run?.runId === ctx.runId ? ctx.run?.evidence.some((ev) => ev.topic === 'LOG_ANALYSIS') : false;
      return [
        { id: 'allowed', label: 'Sandboxed tool call allowed by policy', done: !!sandboxed },
        { id: 'analysis', label: 'Log analysis evidence collected', done: !!analysis },
        { id: 'awaiting', label: 'Evidence-backed proposal awaiting approval', done: reachedState(ctx, 'AWAITING_APPROVAL') },
      ];
    }
  }
}
