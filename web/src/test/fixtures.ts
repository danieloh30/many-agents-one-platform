import type { ConsoleConfig, InvocationList, OpsSnapshot, Run, RunList, RunTimeline, ScenarioList } from '../api/types';

export const RUN_ID = '0197a6f2-6c1e-7b3a-9d2e-3f1c5a7b9e10';
export const DENIED_RUN_ID = '0197a6f2-7000-7b3a-9d2e-3f1c5a7b9e11';
export const SPIFFE_REC = 'spiffe://maop.example/ns/maop/sa/recommendation-agent';
export const SPIFFE_EVIDENCE = 'spiffe://maop.example/ns/maop/sa/evidence-worker';

export function config(overrides: Partial<ConsoleConfig> = {}): ConsoleConfig {
  return {
    product: 'Agent Operations Console',
    version: '0.1.0',
    profile: 'demo',
    model: { mode: 'LIVE', name: 'gpt-4o-mini' },
    identity: { mode: 'SPIFFE', trustDomain: 'maop.example' },
    links: { jaeger: 'https://jaeger.example', grafana: null, grafanaDashboard: null },
    limits: { burstMaxRuns: 12, maxEvidenceTasks: 4 },
    features: { faultInjection: true },
    ...overrides,
  };
}

export const scenarios: ScenarioList = { items: [] };

export function run(overrides: Partial<Run> = {}): Run {
  return {
    runId: RUN_ID,
    version: 7,
    state: 'AWAITING_APPROVAL',
    outcome: null,
    scenario: 'SUCCESS',
    remediationMode: 'PROPOSE_ONLY',
    faultInjection: 'NONE',
    planSource: 'MODEL',
    incident: { title: 'Checkout error rate elevated after release', description: '5xx on POST /checkout rose.', service: 'checkout' },
    classification: { category: 'RELEASE_REGRESSION', severity: 'SEV2', confidence: 0.86, rationale: 'Error onset follows the rollout.' },
    createdAt: '2026-01-01T14:10:00Z',
    updatedAt: '2026-01-01T14:11:00Z',
    deadline: '2026-01-01T14:12:00Z',
    approvalDeadline: '2026-01-01T14:22:00Z',
    budget: { maxEvidenceTasks: 4, maxModelCalls: 12, maxToolCallsPerTask: 4, maxTotalTokens: 24000 },
    usage: { modelCalls: 5, toolCalls: 6, inputTokens: 4120, outputTokens: 890, policyDenials: 0 },
    model: { mode: 'LIVE', name: 'gpt-4o-mini' },
    traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    burstId: null,
    evidenceCompleteness: 'COMPLETE',
    tasks: [
      {
        taskId: 'task-1',
        kind: 'EVIDENCE',
        topic: 'DEPLOYMENT_HISTORY',
        state: 'COMPLETED',
        deliveries: 1,
        startedAt: '2026-01-01T14:10:05Z',
        finishedAt: '2026-01-01T14:10:20Z',
      },
    ],
    evidence: [
      {
        evidenceId: 'ev-1',
        taskId: 'task-1',
        topic: 'DEPLOYMENT_HISTORY',
        finding: 'checkout v2.14.0 rolled out at 14:01 UTC.',
        confidence: 0.9,
        facts: [{ statement: 'v2.14.0 rolled out at 14:01.', invocationId: 'inv-1', toolName: 'deployment.get_history' }],
        dataOrigin: 'SYNTHETIC',
        model: { mode: 'LIVE', name: 'gpt-4o-mini' },
      },
    ],
    recommendation: {
      summary: 'Roll back checkout to v2.13.2.',
      explanation: 'The timeout change in v2.14.0 matches the error onset.',
      proposedAction: {
        actionType: 'ROLLBACK_DEPLOYMENT',
        target: 'checkout',
        parameters: { toVersion: '2.13.2' },
        sideEffect: true,
        actionDigest: 'sha256:abcdef0123456789',
      },
      citations: ['ev-1'],
      confidence: 0.82,
      risks: ['Rollback drops the new feature flag.'],
    },
    executionAttempt: null,
    approval: null,
    ...overrides,
  };
}

export function runList(...runs: Run[]): RunList {
  return {
    items: runs.map((r) => ({
      runId: r.runId,
      state: r.state,
      outcome: r.outcome,
      scenario: r.scenario,
      title: r.incident.title,
      service: r.incident.service,
      planSource: r.planSource,
      burstId: null,
      createdAt: r.createdAt,
      updatedAt: r.updatedAt ?? null,
      traceId: r.traceId,
    })),
    nextCursor: null,
  };
}

export const timeline: RunTimeline = {
  items: [
    { seq: 1, at: '2026-01-01T14:10:00Z', kind: 'STATE_CHANGED', from: null, to: 'SUBMITTED', taskId: null, message: null, data: null },
    { seq: 2, at: '2026-01-01T14:10:01Z', kind: 'STATE_CHANGED', from: 'SUBMITTED', to: 'TRIAGING', taskId: null, message: null, data: null },
  ],
};

export const invocations: InvocationList = {
  items: [
    {
      invocationId: 'inv-1',
      runId: RUN_ID,
      taskId: 'task-1',
      toolName: 'deployment.get_history',
      toolClass: 'READ',
      status: 'EXECUTED',
      requests: 1,
      executions: 1,
      idempotencyKey: 'task-1:deployment.get_history:0123456789abcdef',
      simulated: true,
      dataOrigin: 'SYNTHETIC',
      caller: { spiffeId: SPIFFE_EVIDENCE, serial: '1a2b', notAfter: '2026-01-01T15:00:00Z' },
      decision: { allow: true, decisionId: 'dec-allow-1', policyRevision: 'rev-1', reasons: [] },
      arguments: { service: 'checkout', limit: 5 },
      result: { rollouts: [] },
      sideEffect: null,
      createdAt: '2026-01-01T14:10:06Z',
      lastRequestedAt: '2026-01-01T14:10:06Z',
    },
  ],
};

export const deniedInvocations: InvocationList = {
  items: [
    {
      invocationId: 'inv-denied',
      runId: DENIED_RUN_ID,
      taskId: 'task-rec',
      toolName: 'remediation.rollback_deployment',
      toolClass: 'REMEDIATION',
      status: 'DENIED',
      requests: 1,
      executions: 0,
      idempotencyKey: 'task-rec:remediation.rollback_deployment:fedcba9876543210',
      simulated: true,
      dataOrigin: 'SYNTHETIC',
      caller: { spiffeId: SPIFFE_REC, serial: '7f00aa', notAfter: '2026-01-01T15:00:00Z' },
      decision: { allow: false, decisionId: 'dec-deny-42', policyRevision: 'rev-9', reasons: ['approval_required'] },
      arguments: { service: 'checkout', toVersion: '2.13.2' },
      result: null,
      sideEffect: null,
      createdAt: '2026-01-01T14:11:00Z',
      lastRequestedAt: '2026-01-01T14:11:00Z',
    },
  ],
};

export function snapshot(overrides: Partial<OpsSnapshot> = {}): OpsSnapshot {
  return {
    observedAt: new Date().toISOString(),
    queues: [
      { stream: 'MAOP_WORK', consumer: 'evidence-workers', status: 'OK', pending: 3, inFlight: 1, redelivered: 0, oldestMessageAgeSeconds: 2.5 },
    ],
    workloads: [
      {
        name: 'evidence-worker',
        status: 'OK',
        desiredReplicas: 1,
        readyReplicas: 1,
        minReplicas: 1,
        maxReplicas: 6,
        scaler: { kind: 'KEDA', active: false, ready: true },
        pods: [{ name: 'evidence-worker-7c9d', phase: 'Running', ready: true, startedAt: '2026-01-01T14:00:00Z', activeTasks: 1 }],
      },
    ],
    platform: [
      { component: 'opa', status: 'OK' },
      { component: 'spire', status: 'OK' },
    ],
    ...overrides,
  };
}
