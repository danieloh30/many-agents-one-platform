import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CircleStop, TriangleExclamationPoint } from 'lucide-react';
import { api, ApiError, describeError, queryKeys } from '../../api/client';
import { useRun, useTimeline } from '../../api/queries';
import type { Run } from '../../api/types';
import { useNotify } from '../../app/notify';
import { useCanMutate, useSession } from '../../app/session';
import { ConfirmDialog } from '../../design/ConfirmDialog';
import { humanize } from '../../design/format';
import { isTerminal, RunStatePill } from '../../design/runState';
import { Card, GatedButton, Pill, Skeleton } from '../../design/ui';
import { DeadlineBar } from './DeadlineBar';
import { EvidenceCards } from './EvidenceCards';
import { ModelBadge } from './ModelBadge';
import { RecommendationCard } from './RecommendationCard';
import { StateRail } from './StateRail';
import { TaskLanes } from './TaskLanes';
import { TraceLink } from './TraceLink';

export function RunHeader({ run }: { run: Run }) {
  return (
    <header className="flex flex-wrap items-center gap-2">
      <h2 className="mr-auto min-w-0 text-xl font-semibold" data-testid="run-title">
        {run.incident.title}
      </h2>
      <RunStatePill state={run.state} outcome={run.outcome} />
      <Pill tone="neutral">{humanize(run.scenario)}</Pill>
      {run.faultInjection !== 'NONE' ? (
        <Pill tone="warn" icon={TriangleExclamationPoint}>
          Fault injected: {run.faultInjection}
        </Pill>
      ) : null}
      <ModelBadge model={run.model} />
      <TraceLink traceId={run.traceId} />
      <CancelButton run={run} />
    </header>
  );
}

function CancelButton({ run }: { run: Run }) {
  const qc = useQueryClient();
  const { token, reason } = useCanMutate();
  const toast = useNotify((s) => s.toast);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  if (isTerminal(run.state)) return null;
  const cancel = async () => {
    if (!token) return;
    setBusy(true);
    try {
      await api.cancel(run.runId, token);
      void qc.invalidateQueries({ queryKey: queryKeys.run(run.runId) });
      void qc.invalidateQueries({ queryKey: queryKeys.runs });
      setOpen(false);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) useSession.getState().markRejected();
      toast({ tone: 'error', title: 'Cancel failed', description: describeError(e) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <GatedButton size="sm" variant="ghost" icon={CircleStop} disabledReason={reason} onClick={() => setOpen(true)}>
        Cancel run
      </GatedButton>
      <ConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title="Cancel this run?"
        body={<p>The run moves to Cancelled. Nothing outside the demonstration is affected.</p>}
        confirmLabel="Cancel run"
        tone="danger"
        busy={busy}
        onConfirm={() => void cancel()}
      />
    </>
  );
}

export function RunTimelineView({ runId }: { runId: string | undefined }) {
  const run = useRun(runId);
  const timeline = useTimeline(runId);
  if (!runId) {
    return (
      <div className="grid h-full place-items-center p-6 text-center text-text-muted">
        <p>Select a run from Recent runs, or run a scenario, to see its timeline.</p>
      </div>
    );
  }
  if (run.isPending) {
    return (
      <div className="space-y-3 p-4">
        <Skeleton className="h-8 w-2/3" label="Loading run" />
        <Skeleton className="h-16" label="Loading run" />
        <Skeleton className="h-40" label="Loading run" />
      </div>
    );
  }
  if (run.isError) {
    return (
      <div role="alert" className="p-4 text-sm text-danger">
        Run could not be loaded: {describeError(run.error)}
      </div>
    );
  }
  const r = run.data;
  const items = timeline.data?.items ?? [];
  return (
    <div className="space-y-3 p-3" aria-label="Run timeline">
      <RunHeader run={r} />
      <Card title="State">
        <StateRail run={r} timeline={items} />
        <div className="mt-3">
          <DeadlineBar run={r} />
        </div>
        {timeline.isError ? (
          <p className="mt-2 text-sm text-danger">Timeline unavailable: {describeError(timeline.error)}</p>
        ) : null}
      </Card>
      <Card title="Tasks and deliveries">
        <TaskLanes run={r} timeline={items} />
      </Card>
      <section aria-labelledby="evidence-h">
        <h3 id="evidence-h" className="mb-2 text-sm font-semibold uppercase tracking-wide text-text-muted">
          Evidence
          {r.evidenceCompleteness ? <span className="ml-2 normal-case">({humanize(r.evidenceCompleteness)})</span> : null}
        </h3>
        <EvidenceCards runId={r.runId} evidence={r.evidence} />
      </section>
      <RecommendationCard run={r} />
    </div>
  );
}
