import { useLiveStore } from '../../api/liveStore';
import { useConfig, useOpsSnapshot } from '../../api/queries';
import type { TopologyNodeId } from '../../app/uiStore';
import { fmtNum, fmtSeconds, fmtTime } from '../../design/format';
import { KeyValue, StatusPill, Value } from '../../design/ui';
import { modelComponent, platform, queue, workload } from '../ops/selectors';
import { describeEvent, eventMatchesRun, NODE_LABELS, nodesForEvent } from './graph';

const WORKLOAD_FOR: Partial<Record<TopologyNodeId, string>> = {
  triage: 'triage-agent',
  'evidence-workers': 'evidence-worker',
  recommendation: 'recommendation-agent',
  'tool-service': 'tool-service',
};
const QUEUE_FOR: Partial<Record<TopologyNodeId, string>> = {
  'evidence-queue': 'evidence-workers',
  'recommendation-queue': 'recommendation-workers',
};

export function ElementDetails({ nodeId, runId }: { nodeId: TopologyNodeId; runId: string | undefined }) {
  const snap = useOpsSnapshot().data;
  const config = useConfig().data;
  const events = useLiveStore((s) => s.events);
  const related = events
    .filter((e) => eventMatchesRun(e, runId) && nodesForEvent(e).includes(nodeId))
    .slice(-6)
    .reverse();
  const wl = WORKLOAD_FOR[nodeId] ? workload(snap, WORKLOAD_FOR[nodeId]!) : undefined;
  const q = QUEUE_FOR[nodeId] ? queue(snap, QUEUE_FOR[nodeId]!) : undefined;
  const items: Array<[string, React.ReactNode]> = [];
  if (WORKLOAD_FOR[nodeId]) {
    items.push(['Status', <StatusPill status={wl?.status} />]);
    items.push(['Replicas ready / desired', <Value value={wl ? `${fmtNum(wl.readyReplicas)} / ${fmtNum(wl.desiredReplicas)}` : null} />]);
  }
  if (QUEUE_FOR[nodeId]) {
    items.push(['Status', <StatusPill status={q?.status} />]);
    items.push(['Pending', <Value value={q ? fmtNum(q.pending) : null} />]);
    items.push(['In flight', <Value value={q ? fmtNum(q.inFlight) : null} />]);
    items.push(['Oldest message', <Value value={q ? fmtSeconds(q.oldestMessageAgeSeconds) : null} />]);
  }
  if (nodeId === 'opa') items.push(['Status', <StatusPill status={platform(snap, (n) => n === 'opa')?.status} />]);
  if (nodeId === 'model') {
    items.push(['Status', <StatusPill status={modelComponent(snap)?.status} />]);
    items.push(['Mode', config?.model.mode ?? 'Unavailable']);
    items.push(['Model', config?.model.name ?? 'Unavailable']);
  }
  return (
    <div className="space-y-2 text-sm">
      <p className="font-semibold">{NODE_LABELS[nodeId]}</p>
      {items.length > 0 ? <KeyValue items={items} /> : null}
      <div>
        <p className="text-text-muted">Recent related events</p>
        {related.length === 0 ? (
          <p className="text-text-muted">None since page load.</p>
        ) : (
          <ul className="mt-1 space-y-0.5">
            {related.map((e) => (
              <li key={`${e.type}:${e.id}`}>
                <span className="text-text-muted tabular">{fmtTime(e.time ?? new Date(e.receivedAt).toISOString())}</span>{' '}
                {describeEvent(e)}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
