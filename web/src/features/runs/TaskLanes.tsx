import { Link } from 'react-router';
import { RotateCcw } from 'lucide-react';
import { useInvocations } from '../../api/queries';
import type { Run, RunTimelineEntry, ToolInvocation } from '../../api/types';
import { fmtTime, humanize } from '../../design/format';
import { cx, Pill } from '../../design/ui';
import { SANDBOX_MARK, sandboxOf } from '../audit/InvocationDetail';

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

const times = (n: number | null) => (n === null ? 'an unknown number of times' : n === 1 ? 'once' : `${n} times`);

/** What the tool audit says about a redelivered task's tool calls: requested again, executed once. */
function ReplayNote({ runId, calls }: { runId: string; calls: ToolInvocation[] }) {
  return (
    <ul className="col-span-full mb-1 ml-2 space-y-0.5 border-l-2 border-warn pl-2 text-xs text-text-muted" aria-label="Repeated tool calls">
      {calls.map((inv) => (
        <li key={inv.invocationId}>
          <span className="font-mono text-text">{inv.toolName}</span>: requested {times(inv.requests)}, executed{' '}
          {times(inv.executions)}
          {inv.executions === 1 ? '; the repeat got the recorded result back.' : '.'}{' '}
          <Link className="text-primary underline" to={`/runs/${runId}/audit/${inv.invocationId}`}>
            Open in Tool audit
          </Link>
        </li>
      ))}
    </ul>
  );
}

/**
 * Why a sandboxed-analysis run has one more task: code, not the model, added a Log analysis task that calls the
 * sandboxed tool. The sandbox mode is read from the recorded call, never assumed.
 */
function SandboxNote({ runId, invocations }: { runId: string; invocations: ToolInvocation[] }) {
  const inv = invocations.find((i) => i.toolClass === 'SANDBOXED');
  const mode = inv ? sandboxOf(inv.result).mode : null;
  return (
    <p className="text-xs text-text-muted" data-testid="sandbox-note">
      The <span className="text-text">Log analysis</span> task runs a fixed analysis script through the sandboxed tool behind
      the tool service. Policy decides first, and only the evidence agent holds the permission.{' '}
      {inv ? (
        <>
          <span className={SANDBOX_MARK}>
            Sandbox mode: <span className="font-mono">{mode ?? 'not reported'}</span>
          </span>
          {mode === 'SIMULATED' ? ' (the script was recorded, not executed).' : '.'}{' '}
          <Link className="text-primary underline" to={`/runs/${runId}/audit/${inv.invocationId}`}>
            Open in Tool audit
          </Link>
        </>
      ) : (
        'The call has not been recorded yet.'
      )}
    </p>
  );
}

export function TaskLanes({ run, timeline }: { run: Run; timeline: RunTimelineEntry[] }) {
  const invocations = useInvocations(run.runId).data?.items ?? [];
  if (run.tasks.length === 0) return <p className="text-sm text-text-muted">No tasks dispatched yet.</p>;
  return (
    <div className="@container space-y-2">
      {run.faultInjection === 'DROP_ACK_ONCE' ? (
        <p className="text-xs text-text-muted">
          One evidence task's acknowledgement is dropped once; JetStream redelivers it after the acknowledgement timeout
          (about 20 s), and the lane gains a <span className="text-warn">delivery 2</span>.
        </p>
      ) : null}
      {run.analysisMode === 'SANDBOXED_SCRIPT' ? <SandboxNote runId={run.runId} invocations={invocations} /> : null}
      <ul className="space-y-1.5" aria-label="Task lanes">
        {run.tasks.map((t) => {
          const segs = segmentsFor(t.taskId, t.deliveries, timeline);
          const redelivered = segs.length > 1;
          const repeated = redelivered ? invocations.filter((i) => i.taskId === t.taskId && (i.requests ?? 0) >= 2) : [];
          return (
            <li key={t.taskId} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-2 text-sm @md:grid-cols-[minmax(9rem,14rem)_minmax(0,1fr)_auto]">
              <span className="col-span-full truncate font-medium @md:col-span-1" title={t.taskId}>
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
                {redelivered ? (
                  <Pill tone="warn" icon={RotateCcw} testId="redelivered-pill">
                    Redelivered
                  </Pill>
                ) : null}
                <Pill tone={stateTone(t.state)}>{humanize(t.state)}</Pill>
              </span>
              {repeated.length > 0 ? <ReplayNote runId={run.runId} calls={repeated} /> : null}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
