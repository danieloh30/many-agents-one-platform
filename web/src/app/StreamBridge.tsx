import { useEffect } from 'react';
import { useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query';
import { queryKeys } from '../api/client';
import { useOpsSnapshot } from '../api/queries';
import type { LiveEvent } from '../api/events';
import { useLiveStore } from '../api/liveStore';
import { EventStreamClient, type ResyncReason } from '../api/streamClient';
import type { BurstStatus } from '../api/types';
import { humanize } from '../design/format';
import { useNotify } from './notify';
import { useUi } from './uiStore';

/** Batches invalidations so a burst of events causes one refetch per query. */
function createInvalidator(qc: QueryClient, delayMs = 250) {
  const pending = new Map<string, QueryKey>();
  let timer: ReturnType<typeof setTimeout> | null = null;
  return {
    add(...keys: QueryKey[]) {
      for (const k of keys) pending.set(JSON.stringify(k), k);
      if (timer) return;
      timer = setTimeout(() => {
        timer = null;
        const keys = [...pending.values()];
        pending.clear();
        for (const k of keys) void qc.invalidateQueries({ queryKey: k, exact: true });
      }, delayMs);
    },
    cancel() {
      if (timer) clearTimeout(timer);
      timer = null;
      pending.clear();
    },
  };
}

/** Owns the single EventSource and routes what it receives into the query cache and live store. */
export function StreamBridge({ selectedRunId }: { selectedRunId: string | undefined }) {
  const qc = useQueryClient();
  const snapshot = useOpsSnapshot().data;

  useEffect(() => {
    useUi.setState({ selectedRunId });
  }, [selectedRunId]);

  // REST snapshots (initial load, refetch after reconnect) also feed the since-page-load history.
  useEffect(() => {
    if (snapshot) useLiveStore.getState().addSnapshot(snapshot);
  }, [snapshot]);

  useEffect(() => {
    const live = useLiveStore.getState();
    const notify = useNotify.getState();
    const inv = createInvalidator(qc);

    const onRunEvent = (e: LiveEvent) => {
      live.addEvent(e);
      const runId = e.runId;
      const selected = useUi.getState().selectedRunId;
      switch (e.type) {
        case 'run.state_changed.v1':
          inv.add(queryKeys.runs);
          if (runId) inv.add(queryKeys.run(runId), queryKeys.timeline(runId));
          if (runId && runId === selected) notify.announce(`Selected run is now ${humanize(e.data.to)}`);
          break;
        case 'task.dispatched.v1':
        case 'task.started.v1':
        case 'task.evidence.completed.v1':
        case 'task.recommendation.completed.v1':
        case 'task.action.completed.v1':
        case 'task.failed.v1':
        case 'task.dead_lettered.v1':
          if (runId) inv.add(queryKeys.run(runId), queryKeys.timeline(runId));
          break;
        case 'model.call.completed.v1':
          if (runId) inv.add(queryKeys.run(runId));
          break;
        case 'tool.invocation.completed.v1':
          if (runId) inv.add(queryKeys.invocations(runId), queryKeys.run(runId));
          if (e.data.status === 'DENIED') {
            const reasons = e.data.reasons?.length ? e.data.reasons.join(', ') : 'no reason reported';
            notify.toast({
              tone: 'policy',
              title: `Policy denied ${e.data.toolName}`,
              description: `${reasons}. No side effect — denied before execution.`,
            });
            if (!selected || runId === selected) notify.announce(`Policy denied ${e.data.toolName}: ${reasons}`);
          }
          break;
        case 'burst.changed.v1': {
          const prev = qc.getQueryData<BurstStatus>(queryKeys.burst);
          qc.setQueryData<BurstStatus>(queryKeys.burst, {
            active: e.data.active,
            burstId: e.data.burstId ?? prev?.burstId ?? null,
            runs: e.data.runs ?? prev?.runs ?? null,
            completed: e.data.completed ?? prev?.completed ?? null,
            startedAt: prev?.startedAt ?? null,
          });
          inv.add(queryKeys.burst, queryKeys.runs);
          break;
        }
        default:
          break;
      }
    };

    const onResync = (reason: ResyncReason) => {
      if (reason === 'demo-reset') {
        live.clearEvents();
        useUi.getState().resetDemoState();
        qc.removeQueries({ predicate: (q) => ['run', 'timeline', 'invocations', 'invocation'].includes(String(q.queryKey[0])) });
      }
      // Durable state wins: refetch everything that is on screen from REST.
      void qc.invalidateQueries();
    };

    const client = new EventStreamClient({
      onStatus: (s) => useLiveStore.getState().setStatus(s),
      onSnapshot: (s) => {
        qc.setQueryData(queryKeys.snapshot, s);
        useLiveStore.getState().addSnapshot(s);
      },
      onRunEvent,
      onResync,
    });
    client.start();
    return () => {
      client.stop();
      inv.cancel();
    };
  }, [qc]);

  return null;
}
