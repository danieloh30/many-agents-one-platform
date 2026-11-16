import { z } from 'zod';
import type { OpsSnapshot } from './types';

/**
 * zod validation at the SSE boundary (contracts §2.6, §3.1, §3.4, §5.3).
 * Payloads that fail validation are dropped; unknown event types are ignored.
 */

export const TYPE_PREFIX = 'io.github.danieloh30.maop.';

const status = z.enum(['OK', 'DEGRADED', 'UNAVAILABLE']).catch('UNAVAILABLE');
const nnum = z.number().nullable().catch(null);

export const opsSnapshotSchema = z.looseObject({
  observedAt: z.string(),
  queues: z
    .array(
      z.looseObject({
        stream: z.string(),
        consumer: z.string(),
        status,
        pending: nnum,
        inFlight: nnum,
        redelivered: nnum,
        oldestMessageAgeSeconds: nnum,
      }),
    )
    .catch([]),
  workloads: z
    .array(
      z.looseObject({
        name: z.string(),
        status,
        desiredReplicas: nnum,
        readyReplicas: nnum,
        minReplicas: nnum,
        maxReplicas: nnum,
        scaler: z
          .looseObject({
            kind: z.string().nullable().catch(null),
            active: z.boolean().nullable().catch(null),
            ready: z.boolean().nullable().catch(null),
          })
          .nullable()
          .catch(null),
        pods: z
          .array(
            z.looseObject({
              name: z.string(),
              phase: z.string().nullable().catch(null),
              ready: z.boolean().nullable().catch(null),
              startedAt: z.string().nullable().catch(null),
              activeTasks: nnum,
            }),
          )
          .nullable()
          .catch(null),
      }),
    )
    .catch([]),
  platform: z
    .array(z.looseObject({ component: z.string(), status, detail: z.string().nullable().optional() }))
    .catch([]),
});

export function parseSnapshot(raw: unknown): OpsSnapshot | null {
  const r = opsSnapshotSchema.safeParse(raw);
  return r.success ? (r.data as OpsSnapshot) : null;
}

export const cloudEventSchema = z.looseObject({
  specversion: z.string(),
  id: z.string(),
  source: z.string(),
  type: z.string(),
  subject: z.string().optional(),
  time: z.string().optional(),
  data: z.unknown().optional(),
  maoprunid: z.string().optional(),
  maoptaskid: z.string().optional(),
  maopmodelmode: z.string().optional(),
});

const optStr = z.string().nullable().optional();
const optNum = z.number().nullable().optional();

const dataSchemas = {
  'run.state_changed.v1': z.looseObject({
    runId: z.string(),
    from: optStr,
    to: z.string(),
    outcome: optStr,
    version: optNum,
    reason: optStr,
  }),
  'task.dispatched.v1': z.looseObject({ taskId: z.string(), kind: z.string(), topic: optStr }),
  'task.started.v1': z.looseObject({
    taskId: z.string(),
    kind: z.string(),
    pod: optStr,
    delivery: optNum,
    service: optStr,
  }),
  'model.call.completed.v1': z.looseObject({
    taskId: optStr,
    purpose: optStr,
    model: optStr,
    mode: optStr,
    durationMs: optNum,
    inputTokens: optNum,
    outputTokens: optNum,
    status: optStr,
    service: optStr,
  }),
  'tool.invocation.completed.v1': z.looseObject({
    invocationId: z.string(),
    toolName: z.string(),
    toolClass: optStr,
    status: z.enum(['EXECUTED', 'DENIED', 'REPLAYED']).or(z.string()),
    callerSpiffeId: optStr,
    decisionId: optStr,
    simulated: z.boolean().nullable().optional(),
    runId: optStr,
    taskId: optStr,
    reasons: z.array(z.string()).nullable().optional(),
  }),
  'worker.heartbeat.v1': z.looseObject({
    service: z.string(),
    pod: z.string(),
    activeTasks: optNum,
    modelPermitsInUse: optNum,
  }),
  'task.evidence.completed.v1': z.looseObject({ taskId: z.string() }),
  'task.recommendation.completed.v1': z.looseObject({ taskId: z.string() }),
  'task.action.completed.v1': z.looseObject({ taskId: z.string(), invocationId: optStr, status: optStr }),
  'task.failed.v1': z.looseObject({ taskId: z.string(), kind: optStr, reason: optStr, deliveries: optNum }),
  'task.dead_lettered.v1': z.looseObject({ taskId: z.string(), kind: optStr, reason: optStr, deliveries: optNum }),
  'burst.changed.v1': z.looseObject({
    burstId: optStr,
    active: z.boolean(),
    runs: optNum,
    completed: optNum,
  }),
  'demo.reset.v1': z.looseObject({}),
} as const;

export type KnownEventType = keyof typeof dataSchemas;
export type EventData<T extends KnownEventType> = z.infer<(typeof dataSchemas)[T]>;

interface BaseLiveEvent {
  /** CloudEvent id. */
  id: string;
  /** SSE id (JetStream sequence), when present. */
  seq: number | null;
  /** CloudEvent source, e.g. /maop/triage-agent. */
  source: string;
  time: string | null;
  runId: string | null;
  taskId: string | null;
  modelMode: string | null;
  receivedAt: number;
}

export type LiveEvent = {
  [K in KnownEventType]: BaseLiveEvent & { type: K; data: EventData<K> };
}[KnownEventType];

export type LiveEventOf<K extends KnownEventType> = Extract<LiveEvent, { type: K }>;

function runIdFromSubject(subject: string | undefined): string | null {
  if (!subject) return null;
  const m = /^runs\/([^/]+)/.exec(subject);
  return m?.[1] ?? null;
}

/**
 * Validates a structured-mode CloudEvent. Returns null for malformed payloads and for event types
 * this console does not render (they are ignored, not errors).
 */
export function parseRunEvent(raw: unknown, sseId: string | null, receivedAt = Date.now()): LiveEvent | null {
  const env = cloudEventSchema.safeParse(raw);
  if (!env.success) return null;
  const ce = env.data;
  if (!ce.type.startsWith(TYPE_PREFIX)) return null;
  const suffix = ce.type.slice(TYPE_PREFIX.length);
  if (!(suffix in dataSchemas)) return null;
  const type = suffix as KnownEventType;
  const parsed = dataSchemas[type].safeParse(ce.data ?? {});
  if (!parsed.success) return null;
  const data = parsed.data as Record<string, unknown>;
  const dataRunId = typeof data.runId === 'string' ? data.runId : null;
  const dataTaskId = typeof data.taskId === 'string' ? data.taskId : null;
  const seq = sseId !== null && /^\d+$/.test(sseId) ? Number(sseId) : null;
  return {
    type,
    data,
    id: ce.id,
    seq,
    source: ce.source,
    time: ce.time ?? null,
    runId: ce.maoprunid ?? dataRunId ?? runIdFromSubject(ce.subject),
    taskId: ce.maoptaskid ?? dataTaskId,
    modelMode: ce.maopmodelmode ?? null,
    receivedAt,
  } as LiveEvent;
}

export function isEvent<K extends KnownEventType>(e: LiveEvent, type: K): e is LiveEventOf<K> {
  return e.type === type;
}
