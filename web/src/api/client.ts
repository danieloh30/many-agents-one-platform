import type {
  ApprovalRequest,
  BurstRequest,
  BurstStatus,
  ConsoleConfig,
  CreateRunRequest,
  InvocationList,
  OpsSnapshot,
  Problem,
  Run,
  RunList,
  RunTimeline,
  ScenarioList,
  ToolInvocation,
} from './types';

export const API_BASE = '/api/v1';

/** An HTTP error response, carrying the RFC 9457 problem body when the server sent one. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly detail: string | null;
  readonly traceId: string | null;

  constructor(status: number, problem: Problem | null, fallback: string) {
    super(problem?.detail ?? problem?.title ?? fallback);
    this.name = 'ApiError';
    this.status = status;
    this.code = problem?.code ?? null;
    this.detail = problem?.detail ?? problem?.title ?? null;
    this.traceId = problem?.traceId ?? null;
  }
}

/** The request never produced an HTTP response (server down, DNS, connection reset). */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super('The console API could not be reached.');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

export function isNetworkError(e: unknown): e is NetworkError {
  return e instanceof NetworkError;
}

/** Concise, user-facing description of an error: "CODE — detail". */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    const head = e.code ?? `HTTP ${e.status}`;
    return e.detail ? `${head} — ${e.detail}` : head;
  }
  if (e instanceof NetworkError) return 'API unreachable — check that ops-console is running.';
  if (e instanceof Error) return e.message;
  return 'Unexpected error';
}

interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  token?: string | null;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: opts.signal,
      credentials: 'same-origin',
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new NetworkError(cause);
  }
  if (!res.ok) {
    let problem: Problem | null = null;
    const type = res.headers.get('content-type') ?? '';
    if (type.includes('json')) {
      try {
        problem = (await res.json()) as Problem;
      } catch {
        problem = null;
      }
    }
    throw new ApiError(res.status, problem, `HTTP ${res.status}`);
  }
  if (res.status === 204 || res.status === 205) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Retries only when no HTTP response was received; the same headers (and Idempotency-Key) are reused. */
async function withNetworkRetry<T>(fn: () => Promise<T>, attempts = 3, delayMs = 400): Promise<T> {
  let last: unknown;
  for (let i = 0; i < attempts; i++) {
    try {
      return await fn();
    } catch (e) {
      if (!isNetworkError(e)) throw e;
      last = e;
      if (i < attempts - 1) await new Promise((r) => setTimeout(r, delayMs * (i + 1)));
    }
  }
  throw last;
}

const q = (params: Record<string, string | number | null | undefined>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== null && v !== undefined && v !== '') s.set(k, String(v));
  const str = s.toString();
  return str ? `?${str}` : '';
};

export const api = {
  // ---- reads (open) ----
  config: (signal?: AbortSignal) => request<ConsoleConfig>('/config', { signal }),
  scenarios: (signal?: AbortSignal) => request<ScenarioList>('/scenarios', { signal }),
  runs: (params: { limit?: number; cursor?: string | null; label?: string | null } = {}, signal?: AbortSignal) =>
    request<RunList>(`/runs${q(params)}`, { signal }),
  run: (runId: string, signal?: AbortSignal) => request<Run>(`/runs/${encodeURIComponent(runId)}`, { signal }),
  timeline: (runId: string, signal?: AbortSignal) =>
    request<RunTimeline>(`/runs/${encodeURIComponent(runId)}/timeline`, { signal }),
  invocations: (runId: string, signal?: AbortSignal) =>
    request<InvocationList>(`/audit/invocations${q({ runId })}`, { signal }),
  invocation: (invocationId: string, signal?: AbortSignal) =>
    request<ToolInvocation>(`/audit/invocations/${encodeURIComponent(invocationId)}`, { signal }),
  opsSnapshot: (signal?: AbortSignal) => request<OpsSnapshot>('/ops/snapshot', { signal }),
  burstCurrent: (signal?: AbortSignal) => request<BurstStatus>('/bursts/current', { signal }),

  // ---- presenter session ----
  /** Resolves when the token is valid (204); rejects with ApiError 401 otherwise. */
  session: (token: string) => request<void>('/session', { token }),

  // ---- mutations (Bearer presenter token) ----
  createRun: (body: CreateRunRequest, token: string | null, idempotencyKey: string) =>
    withNetworkRetry(() =>
      request<Run>('/runs', { method: 'POST', body, token, headers: { 'Idempotency-Key': idempotencyKey } }),
    ),
  approve: (runId: string, body: ApprovalRequest, token: string | null) =>
    request<Run>(`/runs/${encodeURIComponent(runId)}/approval`, { method: 'POST', body, token }),
  cancel: (runId: string, token: string | null) =>
    request<Run>(`/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST', token }),
  startBurst: (body: BurstRequest, token: string | null) =>
    request<BurstStatus>('/bursts', { method: 'POST', body, token }),
  stopBurst: (token: string | null) => request<BurstStatus | undefined>('/bursts/stop', { method: 'POST', token }),
  reset: (token: string | null) => request<void>('/admin/reset', { method: 'POST', token }),
};

export const queryKeys = {
  config: ['config'] as const,
  scenarios: ['scenarios'] as const,
  runs: ['runs'] as const,
  run: (id: string) => ['run', id] as const,
  timeline: (id: string) => ['timeline', id] as const,
  invocations: (id: string) => ['invocations', id] as const,
  invocation: (id: string) => ['invocation', id] as const,
  snapshot: ['ops-snapshot'] as const,
  burst: ['burst-current'] as const,
};
