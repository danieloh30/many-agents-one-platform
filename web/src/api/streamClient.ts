import { API_BASE } from './client';
import { parseRunEvent, parseSnapshot, type LiveEvent } from './events';
import type { OpsSnapshot } from './types';

export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

export type ResyncReason = 'reset' | 'reconnected' | 'gap' | 'demo-reset';

export interface StreamHandlers {
  onStatus(status: StreamStatus): void;
  onRunEvent(event: LiveEvent): void;
  onSnapshot(snapshot: OpsSnapshot): void;
  /** Client state may be incomplete: refetch everything from REST. */
  onResync(reason: ResyncReason): void;
}

/** After this many consecutive failed attempts the indicator reads "Offline". */
const OFFLINE_AFTER_FAILURES = 3;
const BACKOFF_MS = [1000, 2000, 5000, 10000];

/**
 * The single SSE connection (contracts §2.6). Native EventSource reconnects by itself and resends
 * Last-Event-ID; when the browser gives up (readyState CLOSED, e.g. an HTTP error) this client
 * reconnects with backoff. The stream only makes the UI live; REST remains the source of truth.
 */
export class EventStreamClient {
  private es: EventSource | null = null;
  private status: StreamStatus = 'connecting';
  private failures = 0;
  private everOpened = false;
  private lastSeq: number | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;

  constructor(
    private readonly handlers: StreamHandlers,
    private readonly url = `${API_BASE}/stream`,
  ) {}

  start(): void {
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.es?.close();
    this.es = null;
  }

  private setStatus(s: StreamStatus) {
    if (s === this.status) return;
    this.status = s;
    this.handlers.onStatus(s);
  }

  private connect() {
    if (this.stopped) return;
    const Impl = globalThis.EventSource;
    if (!Impl) {
      this.setStatus('offline');
      return;
    }
    this.handlers.onStatus(this.status);
    const es = new Impl(this.url);
    this.es = es;

    es.onopen = () => {
      const recovering = this.everOpened || this.failures > 0;
      this.everOpened = true;
      this.failures = 0;
      this.setStatus('live');
      if (recovering) this.handlers.onResync('reconnected');
    };

    es.onerror = () => {
      if (this.stopped) return;
      this.failures += 1;
      const offline = this.failures >= OFFLINE_AFTER_FAILURES;
      this.setStatus(offline ? 'offline' : 'reconnecting');
      if (es.readyState === 2 /* CLOSED */) {
        es.close();
        if (this.es === es) this.es = null;
        const delay = BACKOFF_MS[Math.min(this.failures - 1, BACKOFF_MS.length - 1)] ?? 10000;
        this.retryTimer = setTimeout(() => {
          this.retryTimer = null;
          this.connect();
        }, delay);
      }
    };

    es.addEventListener('run-event', (ev) => this.handleRunEvent(ev as MessageEvent<string>));
    es.addEventListener('ops-snapshot', (ev) => {
      const snap = parseSnapshot(safeJson((ev as MessageEvent<string>).data));
      if (snap) this.handlers.onSnapshot(snap);
    });
    es.addEventListener('reset', () => {
      this.lastSeq = null;
      this.handlers.onResync('reset');
    });
  }

  private handleRunEvent(ev: MessageEvent<string>) {
    const sseId = ev.lastEventId || null;
    const seq = sseId !== null && /^\d+$/.test(sseId) ? Number(sseId) : null;
    if (seq !== null) {
      if (this.lastSeq !== null && seq > this.lastSeq + 1) {
        // Missed events (sequence gap): the cache may be behind the durable state.
        this.handlers.onResync('gap');
      }
      if (this.lastSeq === null || seq > this.lastSeq) this.lastSeq = seq;
    }
    const event = parseRunEvent(safeJson(ev.data), sseId);
    if (!event) return; // malformed or unknown type: ignored
    if (event.type === 'demo.reset.v1') {
      this.handlers.onResync('demo-reset');
      return;
    }
    this.handlers.onRunEvent(event);
  }
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
