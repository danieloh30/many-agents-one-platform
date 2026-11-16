import { TriangleExclamationPoint } from 'lucide-react';
import type { Run, RunTimelineEntry } from '../../api/types';
import { fmtTime, humanize } from '../../design/format';
import { cx, Pill } from '../../design/ui';

interface Segment {
  delivery: number;
  pod: string | null;
  at: string | null;
}

function segmentsFor(taskId: string, deliveries: number | null, timeline: RunTimelineEntry[]): Segment[] {
  const started = timeline
    .filter((e) => e.kind === 'TASK_STARTED' && e.taskId === taskId)
    .sort((a, b) => a.seq - b.seq)
    .map((e, i) => ({
      delivery: typeof e.data?.delivery === 'number' ? e.data.delivery : i + 1,
      pod: typeof e.data?.pod === 'string' ? e.data.pod : null,
      at: e.at,
    }));
  const n = Math.max(deliveries ?? 0, started.length);
  const out: Segment[] = [];
  for (let d = 1; d <= n; d++) out.push(started.find((s) => s.delivery === d) ?? { delivery: d, pod: null, at: null });
  return out;
}

const stateTone = (s: string) =>
  s === 'COMPLETED' ? 'ok' : s === 'FAILED' || s === 'DEAD_LETTERED' || s === 'EXPIRED' ? 'danger' : 'info';

export function TaskLanes({ run, timeline }: { run: Run; timeline: RunTimelineEntry[] }) {
  if (run.tasks.length === 0) return <p className="text-sm text-text-muted">No tasks dispatched yet.</p>;
  const fault = run.faultInjection !== 'NONE';
  return (
    <ul className="space-y-1.5" aria-label="Task lanes">
      {run.tasks.map((t) => {
        const segs = segmentsFor(t.taskId, t.deliveries, timeline);
        return (
          <li key={t.taskId} className="grid grid-cols-[minmax(9rem,14rem)_1fr_auto] items-center gap-2 text-sm">
            <span className="truncate font-medium" title={t.taskId}>
              {humanize(t.kind)}
              {t.topic ? <span className="text-text-muted"> · {humanize(t.topic)}</span> : null}
            </span>
            <span className="flex min-w-0 gap-1">
              {segs.length === 0 ? (
                <span className="text-text-muted">Waiting for delivery</span>
              ) : (
                segs.map((s) => (
                  <span
                    key={s.delivery}
                    title={s.pod ? `Pod ${s.pod}` : undefined}
                    className={cx(
                      'flex-1 truncate rounded border px-1.5 py-0.5',
                      s.delivery > 1 ? 'border-warn tint-warn text-warn' : 'border-primary/50 tint-primary',
                    )}
                  >
                    delivery {s.delivery}
                    {s.at ? <span className="text-text-muted tabular"> · {fmtTime(s.at)}</span> : null}
                  </span>
                ))
              )}
            </span>
            <span className="flex items-center gap-1">
              {fault && t.kind === 'EVIDENCE' ? (
                <Pill tone="warn" icon={TriangleExclamationPoint}>
                  Fault injected
                </Pill>
              ) : null}
              <Pill tone={stateTone(t.state)}>{humanize(t.state)}</Pill>
            </span>
          </li>
        );
      })}
    </ul>
  );
}
