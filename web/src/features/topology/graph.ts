import type { LiveEvent } from '../../api/events';
import type { TopologyNodeId } from '../../app/uiStore';
import { fmtDuration, fmtNum, humanize, workloadOf } from '../../design/format';

/** Hand-authored positions (no auto-layout) for legibility on 1920×1080 and 1280×720. */
export const NODE_POSITIONS: Record<TopologyNodeId, { x: number; y: number }> = {
  console: { x: 0, y: 210 },
  triage: { x: 230, y: 210 },
  'evidence-queue': { x: 490, y: 60 },
  'evidence-workers': { x: 750, y: 40 },
  'recommendation-queue': { x: 490, y: 360 },
  recommendation: { x: 750, y: 360 },
  'tool-service': { x: 1030, y: 210 },
  opa: { x: 1270, y: 210 },
  model: { x: 490, y: 560 },
};

export const NODE_LABELS: Record<TopologyNodeId, string> = {
  console: 'Agent Operations Console',
  triage: 'Triage agent',
  'evidence-queue': 'Work queue: evidence',
  'evidence-workers': 'Evidence workers',
  'recommendation-queue': 'Work queue: recommendation',
  recommendation: 'Recommendation agent',
  'tool-service': 'Tool service',
  opa: 'Open Policy Agent',
  model: 'Model endpoint (external)',
};

/** Reading order for keyboard focus. */
export const NODE_ORDER: TopologyNodeId[] = [
  'console',
  'triage',
  'evidence-queue',
  'evidence-workers',
  'recommendation-queue',
  'recommendation',
  'tool-service',
  'opa',
  'model',
];

export type EdgeKind = 'http' | 'async' | 'mtls' | 'policy' | 'model' | 'result';

export interface EdgeDef {
  id: string;
  source: TopologyNodeId;
  target: TopologyNodeId;
  kind: EdgeKind;
  label: string;
}

export const EDGES: EdgeDef[] = [
  { id: 'console-triage', source: 'console', target: 'triage', kind: 'http', label: 'Submit incident' },
  { id: 'triage-evq', source: 'triage', target: 'evidence-queue', kind: 'async', label: 'Evidence command' },
  { id: 'evq-workers', source: 'evidence-queue', target: 'evidence-workers', kind: 'async', label: 'Task delivered' },
  { id: 'workers-tool', source: 'evidence-workers', target: 'tool-service', kind: 'mtls', label: 'Tool call (mTLS)' },
  { id: 'tool-opa', source: 'tool-service', target: 'opa', kind: 'policy', label: 'Policy query' },
  { id: 'triage-recq', source: 'triage', target: 'recommendation-queue', kind: 'async', label: 'Recommendation command' },
  { id: 'recq-rec', source: 'recommendation-queue', target: 'recommendation', kind: 'async', label: 'Task delivered' },
  { id: 'rec-tool', source: 'recommendation', target: 'tool-service', kind: 'mtls', label: 'Tool call (mTLS)' },
  { id: 'workers-triage', source: 'evidence-workers', target: 'triage', kind: 'result', label: 'Results (events)' },
  { id: 'rec-triage', source: 'recommendation', target: 'triage', kind: 'result', label: 'Results (events)' },
  { id: 'triage-model', source: 'triage', target: 'model', kind: 'model', label: 'Model call' },
  { id: 'workers-model', source: 'evidence-workers', target: 'model', kind: 'model', label: 'Model call' },
  { id: 'rec-model', source: 'recommendation', target: 'model', kind: 'model', label: 'Model call' },
];

function serviceOf(e: LiveEvent): string | null {
  const d = e.data as { service?: string | null };
  if (typeof d.service === 'string' && d.service) return d.service;
  const last = e.source.split('/').filter(Boolean).pop();
  return last ?? null;
}

function isRecommendationKind(kind: string | null | undefined) {
  return kind === 'RECOMMENDATION' || kind === 'ACTION';
}

/** Which edges a real event travels along. Unmapped events animate nothing. */
export function edgesForEvent(e: LiveEvent): string[] {
  switch (e.type) {
    case 'run.state_changed.v1':
      return e.data.to === 'SUBMITTED' || (!e.data.from && e.data.to) ? ['console-triage'] : [];
    case 'task.dispatched.v1':
      return [isRecommendationKind(e.data.kind) ? 'triage-recq' : 'triage-evq'];
    case 'task.started.v1':
      return [isRecommendationKind(e.data.kind) ? 'recq-rec' : 'evq-workers'];
    case 'tool.invocation.completed.v1': {
      const caller = workloadOf(e.data.callerSpiffeId);
      const callerEdge = caller === 'recommendation-agent' ? 'rec-tool' : caller === 'evidence-worker' ? 'workers-tool' : null;
      const out = callerEdge ? [callerEdge] : [];
      out.push('tool-opa');
      return out;
    }
    case 'model.call.completed.v1': {
      const s = serviceOf(e);
      if (s === 'triage-agent') return ['triage-model'];
      if (s === 'evidence-worker') return ['workers-model'];
      if (s === 'recommendation-agent') return ['rec-model'];
      return [];
    }
    case 'task.evidence.completed.v1':
      return ['workers-triage'];
    case 'task.recommendation.completed.v1':
    case 'task.action.completed.v1':
      return ['rec-triage'];
    case 'task.failed.v1':
    case 'task.dead_lettered.v1':
      return [isRecommendationKind(e.data.kind) ? 'rec-triage' : 'workers-triage'];
    default:
      return [];
  }
}

export function nodesForEvent(e: LiveEvent): TopologyNodeId[] {
  const ids = new Set<TopologyNodeId>();
  for (const edgeId of edgesForEvent(e)) {
    const def = EDGES.find((x) => x.id === edgeId);
    if (def) {
      ids.add(def.source);
      ids.add(def.target);
    }
  }
  if (e.type === 'worker.heartbeat.v1') {
    if (e.data.service === 'evidence-worker') ids.add('evidence-workers');
    if (e.data.service === 'recommendation-agent') ids.add('recommendation');
    if (e.data.service === 'triage-agent') ids.add('triage');
  }
  return [...ids];
}

export function eventMatchesRun(e: LiveEvent, runId: string | undefined): boolean {
  return !runId || e.runId === runId;
}

const shortId = (id: string | null) => (id ? id.slice(0, 8) : 'unknown');

/** One-line, user-facing description of an event for the Activity list (no prompts or completions). */
export function describeEvent(e: LiveEvent): string {
  switch (e.type) {
    case 'run.state_changed.v1':
      return `Run ${shortId(e.runId)} moved ${e.data.from ? `from ${humanize(e.data.from)} ` : ''}to ${humanize(e.data.to)}${
        e.data.outcome ? ` (${humanize(e.data.outcome)})` : ''
      }`;
    case 'task.dispatched.v1':
      return `Triage dispatched a ${humanize(e.data.kind).toLowerCase()} task${e.data.topic ? ` (${humanize(e.data.topic)})` : ''} to the work queue`;
    case 'task.started.v1':
      return `${e.data.service ?? 'Worker'}${e.data.pod ? ` pod ${e.data.pod}` : ''} started a ${humanize(e.data.kind).toLowerCase()} task${
        typeof e.data.delivery === 'number' ? ` (delivery ${e.data.delivery})` : ''
      }`;
    case 'model.call.completed.v1': {
      const parts = [
        typeof e.data.durationMs === 'number' ? fmtDuration(e.data.durationMs) : null,
        typeof e.data.inputTokens === 'number' ? `${fmtNum(e.data.inputTokens)} in / ${fmtNum(e.data.outputTokens)} out tokens` : null,
        e.data.mode ?? e.modelMode,
        e.data.status,
      ].filter(Boolean);
      return `${serviceOf(e) ?? 'Agent'} model call${e.data.purpose ? ` (${humanize(e.data.purpose).toLowerCase()})` : ''}${
        parts.length ? ` — ${parts.join(', ')}` : ''
      }`;
    }
    case 'tool.invocation.completed.v1': {
      const who = workloadOf(e.data.callerSpiffeId) ?? 'unknown caller';
      const reasons = e.data.reasons?.length ? ` (${e.data.reasons.join(', ')})` : '';
      return `Tool ${e.data.toolName} ${e.data.status} for ${who}${reasons}${e.data.simulated ? ' — simulated' : ''}`;
    }
    case 'worker.heartbeat.v1':
      return `${e.data.service} pod ${e.data.pod} heartbeat — ${fmtNum(e.data.activeTasks)} active tasks`;
    case 'task.evidence.completed.v1':
      return 'Evidence task completed';
    case 'task.recommendation.completed.v1':
      return 'Recommendation completed';
    case 'task.action.completed.v1':
      return `Simulated action executed${e.data.status ? ` (${e.data.status})` : ''}`;
    case 'task.failed.v1':
      return `Task failed: ${humanize(e.data.reason)}`;
    case 'task.dead_lettered.v1':
      return `Task dead-lettered after ${fmtNum(e.data.deliveries)} deliveries: ${humanize(e.data.reason)}`;
    case 'burst.changed.v1':
      return e.data.active
        ? `Burst active — ${fmtNum(e.data.completed)} of ${fmtNum(e.data.runs)} runs completed`
        : 'Burst finished';
    case 'demo.reset.v1':
      return 'Demonstration state reset';
  }
}
