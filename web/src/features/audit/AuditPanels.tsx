import { useNavigate } from 'react-router';
import { RotateCcw } from 'lucide-react';
import { describeError } from '../../api/client';
import { useInvocation, useInvocations, useRun } from '../../api/queries';
import type { ToolInvocation } from '../../api/types';
import { fmtTime, humanize, workloadOf } from '../../design/format';
import { cx, DecisionPill, Pill, Skeleton } from '../../design/ui';
import { CallerIdentity, EffectText, InvocationDetail } from './InvocationDetail';

function useAudit(runId: string) {
  const q = useInvocations(runId);
  const items = [...(q.data?.items ?? [])].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  return { q, items };
}

function Loading({ q }: { q: ReturnType<typeof useInvocations> }) {
  if (q.isPending) return <Skeleton className="h-24" label="Loading tool audit" />;
  if (q.isError) return <p className="text-sm text-danger">Tool audit unavailable: {describeError(q.error)}</p>;
  return null;
}

/** Policy & identity tab: who called what, what policy decided, and the effect. */
export function PolicyIdentityPanel({ runId }: { runId: string }) {
  const { q, items } = useAudit(runId);
  if (q.isPending || q.isError) return <Loading q={q} />;
  if (items.length === 0) return <p className="text-sm text-text-muted">No tool invocations recorded for this run.</p>;
  const ordered = [...items].sort((a, b) => Number(b.status === 'DENIED') - Number(a.status === 'DENIED'));
  return (
    <ul className="space-y-2">
      {ordered.map((inv) => (
        <li
          key={inv.invocationId}
          className={cx('rounded-lg border p-2.5 text-sm', inv.status === 'DENIED' ? 'border-danger/60' : 'border-border')}
        >
          <div className="flex flex-wrap items-center gap-1.5">
            <DecisionPill status={inv.status} />
            <span className="font-mono font-semibold">{inv.toolName}</span>
            <span className="text-text-muted">({inv.toolClass})</span>
          </div>
          {inv.decision.reasons.length > 0 ? (
            <p className="mt-1">
              Reason:{' '}
              {inv.decision.reasons.map((r) => (
                <span key={r} className="mr-1 rounded border border-border bg-surface-raised px-1.5 font-mono">
                  {r}
                </span>
              ))}
            </p>
          ) : null}
          <div className="mt-1.5">
            <CallerIdentity inv={inv} compact={inv.status !== 'DENIED'} />
          </div>
          <p className="mt-1.5">
            <EffectText inv={inv} />
          </p>
        </li>
      ))}
    </ul>
  );
}

/** Tool audit tab: every invocation, with one opened in detail (deep-linkable). */
export function ToolAuditPanel({ runId, invocationId }: { runId: string; invocationId: string | undefined }) {
  const navigate = useNavigate();
  const { q, items } = useAudit(runId);
  const detail = useInvocation(invocationId);
  const tasks = useRun(runId).data?.tasks ?? [];
  const taskName = (taskId: string | null) => {
    const t = tasks.find((x) => x.taskId === taskId);
    return t ? `${humanize(t.topic ?? t.kind)} task` : null;
  };
  const selected: ToolInvocation | undefined = detail.data ?? items.find((i) => i.invocationId === invocationId);
  if (q.isPending || q.isError) return <Loading q={q} />;
  return (
    <div className="space-y-3">
      {items.length === 0 ? (
        <p className="text-sm text-text-muted">No tool invocations recorded for this run.</p>
      ) : (
        <table className="w-full text-sm">
          <caption className="sr-only">Tool invocations</caption>
          <thead className="text-left text-text-muted">
            <tr>
              <th className="py-1 font-normal">Decision</th>
              <th className="py-1 font-normal">Tool</th>
              <th className="py-1 font-normal">Caller</th>
              <th className="py-1 font-normal">Time</th>
            </tr>
          </thead>
          <tbody>
            {items.map((inv) => (
              <tr
                key={inv.invocationId}
                className={cx('border-t border-border', inv.invocationId === invocationId && 'bg-surface-raised')}
              >
                <td className="py-1 pr-1">
                  <DecisionPill status={inv.status} />
                </td>
                <td className="py-1 pr-1">
                  <button
                    type="button"
                    className="text-left font-mono text-primary hover:underline"
                    onClick={() => navigate(`/runs/${runId}/audit/${inv.invocationId}`)}
                    aria-current={inv.invocationId === invocationId ? 'true' : undefined}
                  >
                    {inv.toolName}
                  </button>
                  <div className="flex flex-wrap items-center gap-1 text-xs text-text-muted">
                    {taskName(inv.taskId) ? <span data-testid="invocation-task">{taskName(inv.taskId)}</span> : null}
                    {(inv.requests ?? 0) >= 2 ? (
                      <Pill tone="warn" icon={RotateCcw} testId="requested-again" title="Requested again by a redelivered task; executed once">
                        Requested {inv.requests}×
                      </Pill>
                    ) : null}
                  </div>
                </td>
                <td className="py-1 pr-1 text-text-muted">{workloadOf(inv.caller.spiffeId) ?? 'Unknown'}</td>
                <td className="py-1 tabular text-text-muted">{fmtTime(inv.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {invocationId ? (
        <div className="rounded-lg border border-border bg-surface p-3">
          {selected ? (
            <InvocationDetail inv={selected} />
          ) : detail.isPending ? (
            <Skeleton className="h-32" label="Loading invocation" />
          ) : (
            <p className="text-sm text-danger">Invocation unavailable: {describeError(detail.error)}</p>
          )}
        </div>
      ) : items.length > 0 ? (
        <p className="text-sm text-text-muted">Select a tool to see its policy decision, identity, and effect.</p>
      ) : null}
    </div>
  );
}
