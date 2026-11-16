import type { BurstRequest, ConsoleConfig, CreateRunRequest, ScenarioPreset } from '../../api/types';

/**
 * Scenario presets. The server catalogue (GET /api/v1/scenarios, contracts §2.4) is authoritative;
 * these definitions are used only when it returns no items, and produce exactly the requests listed
 * in contracts §2.4 (the console derives CreateRunRequest from a preset, §5.1).
 */
const incident = {
  title: 'Checkout error rate elevated after release',
  description: '5xx on POST /checkout rose from 0.2% to 7% shortly after the latest rollout.',
  service: 'checkout',
};

export const FALLBACK_PRESETS: ScenarioPreset[] = [
  {
    id: 'SUCCESS',
    title: 'Evidence-backed proposal',
    description: 'Agents gather evidence in parallel and propose one action for approval.',
    request: {
      ...incident,
      scenario: 'SUCCESS',
      remediationMode: 'PROPOSE_ONLY',
      faultInjection: 'NONE',
      labels: { source: 'console' },
    },
  },
  {
    id: 'POLICY_DENIAL',
    title: 'Policy denial',
    description: 'Policy denies an unapproved rollback before it runs.',
    request: {
      ...incident,
      scenario: 'POLICY_DENIAL',
      remediationMode: 'ATTEMPT_UNAPPROVED',
      faultInjection: 'NONE',
      labels: { source: 'console' },
    },
  },
  {
    id: 'BURST',
    title: 'Queue burst',
    description: 'Queue depth rises, workers scale out, the queue drains, replicas return to minimum.',
    request: {},
  },
  {
    id: 'REDELIVERY',
    title: 'Redelivery (optional)',
    description: 'A dropped acknowledgement is redelivered; the tool call executes once and is replayed.',
    request: {
      ...incident,
      scenario: 'REDELIVERY',
      remediationMode: 'PROPOSE_ONLY',
      faultInjection: 'DROP_ACK_ONCE',
      labels: { source: 'console' },
    },
  },
];

export const PRESET_ORDER = ['SUCCESS', 'POLICY_DENIAL', 'BURST', 'REDELIVERY'];

export function resolvePresets(serverItems: ScenarioPreset[] | undefined): ScenarioPreset[] {
  const items = serverItems && serverItems.length > 0 ? serverItems : FALLBACK_PRESETS;
  return [...items].sort((a, b) => rank(a.id) - rank(b.id));
}

function rank(id: string): number {
  const i = PRESET_ORDER.indexOf(id);
  return i === -1 ? PRESET_ORDER.length : i;
}

export function isBurstPreset(p: ScenarioPreset): boolean {
  return p.id === 'BURST';
}

export function isRunRequest(r: ScenarioPreset['request']): r is CreateRunRequest {
  return !!r && typeof r === 'object' && 'title' in r && 'service' in r;
}

export function burstRequest(p: ScenarioPreset): BurstRequest {
  const r = p.request;
  if (r && typeof r === 'object' && 'runs' in r && typeof r.runs === 'number') return { runs: r.runs };
  return {};
}

/** A preset that needs fault injection is disabled when the backend has it switched off. */
export function presetUnavailableReason(p: ScenarioPreset, config: ConsoleConfig | undefined): string | null {
  if (isRunRequest(p.request) && p.request.faultInjection !== 'NONE' && config && !config.features.faultInjection) {
    return 'Fault injection is disabled on this deployment.';
  }
  if (!isBurstPreset(p) && !isRunRequest(p.request)) return 'Preset has no request definition.';
  return null;
}
