import { vi } from 'vitest';

type Listener = (ev: MessageEvent<string>) => void;

/** Controllable stand-in for the browser EventSource. */
export class FakeEventSource {
  static instances: FakeEventSource[] = [];
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;

  readonly url: string;
  readyState = 0;
  onopen: ((ev: Event) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  onmessage: Listener | null = null;
  private listeners = new Map<string, Listener[]>();
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  static latest(): FakeEventSource {
    const es = FakeEventSource.instances.filter((x) => !x.closed).at(-1);
    if (!es) throw new Error('No open FakeEventSource');
    return es;
  }

  static reset() {
    FakeEventSource.instances = [];
  }

  addEventListener(type: string, fn: Listener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }

  removeEventListener(type: string, fn: Listener) {
    this.listeners.set(type, (this.listeners.get(type) ?? []).filter((f) => f !== fn));
  }

  close() {
    this.closed = true;
    this.readyState = 2;
  }

  // ---- test controls ----
  open() {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }

  /** Simulates a dropped connection; native EventSource stays CONNECTING and retries itself. */
  error(closed = false) {
    this.readyState = closed ? 2 : 0;
    this.onerror?.(new Event('error'));
  }

  emit(type: string, data: unknown, id?: string) {
    const ev = new MessageEvent<string>(type, { data: typeof data === 'string' ? data : JSON.stringify(data), lastEventId: id ?? '' });
    for (const fn of this.listeners.get(type) ?? []) fn(ev);
  }
}

export interface RecordedCall {
  method: string;
  path: string;
  headers: Record<string, string>;
  body: unknown;
}

type Handler = (call: RecordedCall) => unknown | Promise<unknown>;

/** A reply with an explicit status (default 200 JSON). */
export function reply(status: number, body?: unknown, contentType = 'application/json'): Response {
  if (status === 204) return new Response(null, { status });
  return new Response(body === undefined ? '' : JSON.stringify(body), {
    status,
    headers: { 'content-type': contentType },
  });
}

/**
 * Installs a fetch mock. Routes are keyed "METHOD /path" (path without query string). A handler may
 * return a Response, a plain object (sent as 200 JSON), or throw to simulate a network failure.
 */
export function mockFetch(routes: Record<string, Handler>) {
  const calls: RecordedCall[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, 'http://localhost');
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((v, k) => (headers[k.toLowerCase()] = v));
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    const call: RecordedCall = { method, path: url.pathname + url.search, headers, body };
    calls.push(call);
    const handler = routes[`${method} ${url.pathname}`];
    if (!handler) return reply(404, { title: 'Not Found', status: 404, code: 'NOT_FOUND' });
    const out = await handler(call);
    return out instanceof Response ? out : reply(200, out);
  });
  vi.stubGlobal('fetch', fn);
  return {
    calls,
    count: (method: string, pathPrefix: string) => calls.filter((c) => c.method === method && c.path.startsWith(pathPrefix)).length,
  };
}
