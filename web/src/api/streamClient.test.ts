import { FakeEventSource } from '../test/fakes';
import { EventStreamClient, type StreamHandlers } from './streamClient';

function setup() {
  FakeEventSource.reset();
  vi.stubGlobal('EventSource', FakeEventSource);
  const h = {
    onStatus: vi.fn(),
    onRunEvent: vi.fn(),
    onSnapshot: vi.fn(),
    onResync: vi.fn(),
  } satisfies StreamHandlers;
  const client = new EventStreamClient(h);
  client.start();
  return { h, client, es: FakeEventSource.latest() };
}

const ce = (type: string, data: unknown) => ({
  specversion: '1.0',
  id: crypto.randomUUID(),
  source: '/maop/triage-agent',
  type: `io.github.danieloh30.maop.${type}`,
  subject: 'runs/r1',
  data,
});

describe('EventStreamClient', () => {
  it('validates run events and ignores unknown or malformed ones', () => {
    const { h, es } = setup();
    es.open();
    es.emit('run-event', ce('run.state_changed.v1', { runId: 'r1', from: 'SUBMITTED', to: 'TRIAGING' }), '1');
    es.emit('run-event', ce('something.new.v1', { x: 1 }), '2');
    es.emit('run-event', ce('run.state_changed.v1', { nope: true }), '3');
    es.emit('run-event', 'not json', '4');
    es.emit('mystery', { a: 1 });
    expect(h.onRunEvent).toHaveBeenCalledTimes(1);
    expect(h.onRunEvent.mock.calls[0]![0]).toMatchObject({ type: 'run.state_changed.v1', runId: 'r1', seq: 1 });
  });

  it('requests a resync on reconnect, on reset, on demo.reset.v1, and on a sequence gap', () => {
    const { h, es } = setup();
    es.open();
    expect(h.onResync).not.toHaveBeenCalled();
    es.error();
    expect(h.onStatus).toHaveBeenLastCalledWith('reconnecting');
    es.open();
    expect(h.onResync).toHaveBeenLastCalledWith('reconnected');
    es.emit('reset', '');
    expect(h.onResync).toHaveBeenLastCalledWith('reset');
    es.emit('run-event', ce('task.started.v1', { taskId: 't', kind: 'EVIDENCE' }), '10');
    es.emit('run-event', ce('task.started.v1', { taskId: 't', kind: 'EVIDENCE' }), '13');
    expect(h.onResync).toHaveBeenLastCalledWith('gap');
    es.emit('run-event', ce('demo.reset.v1', {}), '14');
    expect(h.onResync).toHaveBeenLastCalledWith('demo-reset');
  });

  it('keeps nulls in snapshots and drops invalid snapshots', () => {
    const { h, es } = setup();
    es.emit('ops-snapshot', {
      observedAt: '2026-01-01T00:00:00Z',
      queues: [{ stream: 'S', consumer: 'evidence-workers', status: 'UNAVAILABLE', pending: null, inFlight: null, redelivered: null, oldestMessageAgeSeconds: null }],
      workloads: [],
      platform: [],
    });
    es.emit('ops-snapshot', { nope: 1 });
    expect(h.onSnapshot).toHaveBeenCalledTimes(1);
    expect(h.onSnapshot.mock.calls[0]![0].queues[0].pending).toBeNull();
  });

  it('reconnects with backoff when the browser closes the stream', () => {
    vi.useFakeTimers();
    try {
      const { h, es } = setup();
      es.error(true);
      expect(es.closed).toBe(true);
      vi.advanceTimersByTime(1100);
      expect(FakeEventSource.instances).toHaveLength(2);
      FakeEventSource.latest().open();
      expect(h.onStatus).toHaveBeenLastCalledWith('live');
      expect(h.onResync).toHaveBeenLastCalledWith('reconnected');
    } finally {
      vi.useRealTimers();
    }
  });
});
