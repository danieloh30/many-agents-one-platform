/**
 * Console API types, hand-written to match docs/contracts.md §2.2, §2.4, §2.5 and §5.2 exactly.
 *
 * OpenAPI code generation was dropped for the SPA; these types are the single TypeScript copy of
 * the contracts and must change in the same commit as docs/contracts.md. Readers are tolerant:
 * unknown fields are ignored, and values the backend may omit are typed as nullable.
 */

export type ModelMode = 'LIVE' | 'DETERMINISTIC';
export type ComponentStatus = 'OK' | 'DEGRADED' | 'UNAVAILABLE';

export type RunState =
  | 'SUBMITTED'
  | 'TRIAGING'
  | 'GATHERING_EVIDENCE'
  | 'RECOMMENDING'
  | 'AWAITING_APPROVAL'
  | 'EXECUTING_ACTION'
  | 'COMPLETED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'NEEDS_REVIEW'
  | 'CANCELLED';

export type Scenario = 'SUCCESS' | 'POLICY_DENIAL' | 'BURST' | 'REDELIVERY' | 'CUSTOM';
export type RemediationMode = 'PROPOSE_ONLY' | 'ATTEMPT_UNAPPROVED';
export type FaultInjection = 'NONE' | 'DROP_ACK_ONCE';
export type PlanSource = 'MODEL' | 'PRESET' | (string & {});
export type EvidenceTopic = 'SERVICE_CATALOG' | 'DEPLOYMENT_HISTORY' | 'CONFIG_DIFF' | 'ERROR_SUMMARY';
export type ActionType = 'ROLLBACK_DEPLOYMENT' | 'SET_FEATURE_FLAG' | 'ESCALATE_TO_HUMAN' | 'OBSERVE';
export type TaskKind = 'EVIDENCE' | 'RECOMMENDATION' | 'ACTION' | (string & {});
export type TaskState = 'DISPATCHED' | 'COMPLETED' | 'FAILED' | 'DEAD_LETTERED' | 'EXPIRED' | (string & {});
export type ToolClass = 'READ' | 'REMEDIATION';
export type InvocationStatus = 'EXECUTED' | 'DENIED' | 'REPLAYED';

// ---- §5.2 GET /api/v1/config -------------------------------------------------------------

export interface ConsoleConfig {
  product: string;
  version: string;
  /** Deployment profile name. Never rendered in the UI. */
  profile?: string | null;
  model: { mode: ModelMode; name: string | null };
  identity: { mode: 'SPIFFE' | 'DEV_STATIC'; trustDomain: string | null };
  links: { jaeger: string | null; grafana: string | null; grafanaDashboard: string | null };
  limits: { burstMaxRuns: number | null; maxEvidenceTasks: number | null };
  features: { faultInjection: boolean };
}

// ---- §2.2 CreateRunRequest / §2.4 scenarios ------------------------------------------------

export interface CreateRunRequest {
  title: string;
  description: string;
  service: string;
  scenario: Scenario;
  remediationMode: RemediationMode;
  faultInjection: FaultInjection;
  labels?: Record<string, string>;
}

export interface BurstRequest {
  runs?: number;
}

export interface ScenarioPreset {
  id: Scenario | (string & {});
  title: string;
  description: string;
  /** CreateRunRequest for run presets; burst options (or null) for BURST. */
  request: CreateRunRequest | BurstRequest | null;
}

export interface ScenarioList {
  items: ScenarioPreset[];
}

// ---- §2.2 Run / §5.2 additions -------------------------------------------------------------

export interface Incident {
  title: string;
  description: string;
  service: string;
}

export interface Classification {
  category: string;
  severity: string;
  confidence: number | null;
  rationale: string | null;
}

export interface Budget {
  maxEvidenceTasks: number | null;
  maxModelCalls: number | null;
  maxToolCallsPerTask: number | null;
  maxTotalTokens: number | null;
}

export interface Usage {
  modelCalls: number | null;
  toolCalls: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  policyDenials: number | null;
}

export interface ModelInfo {
  mode: ModelMode;
  name: string | null;
}

export interface RunTask {
  taskId: string;
  kind: TaskKind;
  topic: EvidenceTopic | null;
  state: TaskState;
  deliveries: number | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface EvidenceFact {
  statement: string;
  invocationId: string;
  toolName: string;
}

export interface EvidenceItem {
  evidenceId: string;
  taskId: string;
  topic: EvidenceTopic;
  finding: string;
  confidence: number | null;
  facts: EvidenceFact[];
  dataOrigin: 'SYNTHETIC' | (string & {});
  model: ModelInfo | null;
}

export interface ProposedAction {
  actionType: ActionType;
  target: string;
  parameters: Record<string, unknown>;
  sideEffect: boolean;
  actionDigest: string;
}

export interface Recommendation {
  summary: string;
  explanation: string;
  proposedAction: ProposedAction;
  citations: string[];
  confidence: number | null;
  risks: string[];
}

export interface ExecutionAttempt {
  invocationId: string;
  status: InvocationStatus;
  decisionId: string | null;
  reasons: string[];
}

export interface Approval {
  approvalId: string;
  decision: 'APPROVE' | 'REJECT';
  actionDigest: string;
  approver: string;
  decidedAt: string;
  expiresAt: string | null;
}

export interface Run {
  runId: string;
  version: number;
  state: RunState;
  outcome: string | null;
  scenario: Scenario;
  remediationMode: RemediationMode;
  faultInjection: FaultInjection;
  planSource: PlanSource | null;
  incident: Incident;
  classification: Classification | null;
  createdAt: string;
  updatedAt?: string | null;
  deadline: string | null;
  approvalDeadline: string | null;
  budget: Budget | null;
  usage: Usage | null;
  model: ModelInfo | null;
  traceId: string | null;
  burstId?: string | null;
  evidenceCompleteness?: 'COMPLETE' | 'PARTIAL' | null;
  tasks: RunTask[];
  evidence: EvidenceItem[];
  recommendation: Recommendation | null;
  executionAttempt?: ExecutionAttempt | null;
  approval: Approval | null;
}

export interface RunSummary {
  runId: string;
  state: RunState;
  outcome: string | null;
  scenario: Scenario;
  title: string;
  service: string;
  planSource: PlanSource | null;
  burstId: string | null;
  createdAt: string;
  updatedAt: string | null;
  traceId: string | null;
}

export interface RunList {
  items: RunSummary[];
  nextCursor: string | null;
}

export type TimelineKind =
  | 'STATE_CHANGED'
  | 'TASK_DISPATCHED'
  | 'TASK_STARTED'
  | 'TASK_COMPLETED'
  | 'TASK_FAILED'
  | 'TASK_DEAD_LETTERED'
  | 'POLICY_DENIAL'
  | 'APPROVAL'
  | 'IGNORED_LATE'
  | 'BUDGET'
  | 'NOTE';

export interface RunTimelineEntry {
  seq: number;
  at: string;
  kind: TimelineKind;
  from: string | null;
  to: string | null;
  taskId: string | null;
  message: string | null;
  data: Record<string, unknown> | null;
}

export interface RunTimeline {
  items: RunTimelineEntry[];
}

export interface ApprovalRequest {
  decision: 'APPROVE' | 'REJECT';
  actionDigest: string;
  comment?: string;
}

// ---- §5.2 tool audit ----------------------------------------------------------------------

export interface PolicyDecision {
  allow: boolean;
  decisionId: string | null;
  policyRevision: string | null;
  reasons: string[];
}

export interface VerifiedCaller {
  spiffeId: string;
  serial: string | null;
  notAfter: string | null;
}

export interface SimulatedSideEffect {
  actionId: string;
  actionType: ActionType;
  target: string;
  parameters: Record<string, unknown>;
  simulated: true;
}

export interface ToolInvocation {
  invocationId: string;
  runId: string;
  taskId: string | null;
  toolName: string;
  toolClass: ToolClass;
  status: InvocationStatus;
  requests: number | null;
  executions: number | null;
  idempotencyKey: string;
  simulated: boolean;
  dataOrigin: 'SYNTHETIC' | (string & {});
  caller: VerifiedCaller;
  decision: PolicyDecision;
  arguments: Record<string, unknown> | null;
  result: unknown;
  sideEffect: SimulatedSideEffect | null;
  createdAt: string;
  lastRequestedAt: string | null;
}

export interface InvocationList {
  items: ToolInvocation[];
}

// ---- §2.5 ops snapshot --------------------------------------------------------------------

export interface QueueSnapshot {
  stream: string;
  consumer: string;
  status: ComponentStatus;
  pending: number | null;
  inFlight: number | null;
  redelivered: number | null;
  oldestMessageAgeSeconds: number | null;
}

export interface PodSnapshot {
  name: string;
  phase: string | null;
  ready: boolean | null;
  startedAt: string | null;
  activeTasks: number | null;
}

export interface WorkloadSnapshot {
  name: string;
  status: ComponentStatus;
  desiredReplicas: number | null;
  readyReplicas: number | null;
  minReplicas: number | null;
  maxReplicas: number | null;
  scaler: { kind: string | null; active: boolean | null; ready: boolean | null } | null;
  pods: PodSnapshot[] | null;
}

export interface PlatformComponent {
  component: string;
  status: ComponentStatus;
  detail?: string | null;
}

export interface OpsSnapshot {
  observedAt: string;
  queues: QueueSnapshot[];
  workloads: WorkloadSnapshot[];
  platform: PlatformComponent[];
}

// ---- §5.1 bursts / session -----------------------------------------------------------------

export interface BurstStatus {
  active: boolean;
  burstId: string | null;
  runs: number | null;
  completed: number | null;
  startedAt: string | null;
}

// ---- RFC 9457 problem ---------------------------------------------------------------------

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  traceId?: string;
}
