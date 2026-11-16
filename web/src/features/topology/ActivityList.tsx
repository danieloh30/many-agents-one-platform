import { useLiveStore } from '../../api/liveStore';
import { fmtTime } from '../../design/format';
import { describeEvent, eventMatchesRun } from './graph';

/** Text alternative to the topology: the same live events as an ordered list. */
export function ActivityList({ runId, limit = 40 }: { runId: string | undefined; limit?: number }) {
  const events = useLiveStore((s) => s.events);
  const visible = events.filter((e) => e.type !== 'worker.heartbeat.v1' && eventMatchesRun(e, runId)).slice(-limit);
  return (
    <section aria-labelledby="activity-h" className="text-sm">
      <h3 id="activity-h" className="mb-1 font-semibold text-text-muted">
        Activity list{runId ? ' (selected run)' : ' (all runs)'} — live events since page load
      </h3>
      {visible.length === 0 ? (
        <p className="text-text-muted">No live events received yet.</p>
      ) : (
        <ol className="max-h-56 space-y-0.5 overflow-auto font-mono text-sm">
          {visible.map((e) => (
            <li key={`${e.type}:${e.id}`} className="flex gap-2">
              <span className="shrink-0 text-text-muted tabular">{fmtTime(e.time ?? new Date(e.receivedAt).toISOString())}</span>
              <span className="min-w-0 break-words font-sans">{describeEvent(e)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
