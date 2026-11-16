import { useEffect } from 'react';
import { Dialog } from 'radix-ui';
import { CircleCheck, X } from 'lucide-react';
import { useLiveStore } from '../../api/liveStore';
import { useConfig, useOpsSnapshot } from '../../api/queries';
import { describeError } from '../../api/client';
import type { ConsoleConfig, OpsSnapshot } from '../../api/types';
import { Button, Pill, StatusPill } from '../../design/ui';
import { componentLabel } from '../ops/selectors';

export interface PreflightIssue {
  name: string;
  status: string;
  detail?: string | null;
}

/** Everything that is not reported OK, plus configuration worth knowing before presenting. */
export function preflightIssues(snap: OpsSnapshot | undefined, config: ConsoleConfig | undefined, stream: string): PreflightIssue[] {
  const out: PreflightIssue[] = [];
  if (!snap) out.push({ name: 'Operations snapshot', status: 'UNAVAILABLE', detail: 'No snapshot could be read.' });
  for (const q of snap?.queues ?? []) if (q.status !== 'OK') out.push({ name: `Queue ${q.consumer}`, status: q.status });
  for (const w of snap?.workloads ?? []) if (w.status !== 'OK') out.push({ name: `Workload ${w.name}`, status: w.status });
  for (const p of snap?.platform ?? [])
    if (p.status !== 'OK') out.push({ name: componentLabel(p.component), status: p.status, detail: p.detail });
  if (!config) out.push({ name: 'Console configuration', status: 'UNAVAILABLE' });
  else {
    if (config.model.mode === 'DETERMINISTIC') out.push({ name: 'Model mode', status: 'DETERMINISTIC', detail: 'Scripted model outputs are labelled in the UI.' });
    if (!config.links.jaeger) out.push({ name: 'Jaeger link', status: 'UNAVAILABLE', detail: 'Not exposed; trace links are disabled.' });
    if (!config.links.grafanaDashboard && !config.links.grafana)
      out.push({ name: 'Grafana link', status: 'UNAVAILABLE', detail: 'Not exposed; dashboard link is disabled.' });
  }
  if (stream !== 'live') out.push({ name: 'Live event stream', status: stream.toUpperCase() });
  return out;
}

export function PreflightPanel({ open, onOpenChange }: { open: boolean; onOpenChange(o: boolean): void }) {
  const snap = useOpsSnapshot();
  const config = useConfig();
  const stream = useLiveStore((s) => s.status);
  const refetchSnap = snap.refetch;
  const refetchConfig = config.refetch;
  useEffect(() => {
    if (open) {
      void refetchSnap();
      void refetchConfig();
    }
  }, [open, refetchSnap, refetchConfig]);
  const issues = preflightIssues(snap.data, config.data, stream);
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/50" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[min(94vw,34rem)] -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-surface p-5 text-text shadow-2xl">
          <div className="flex items-center justify-between">
            <Dialog.Title className="text-lg font-semibold">Preflight</Dialog.Title>
            <Dialog.Close asChild>
              <Button size="sm" variant="ghost" icon={X} aria-label="Close preflight" />
            </Dialog.Close>
          </div>
          <Dialog.Description className="mt-1 text-sm text-text-muted">
            Components not reported OK by GET /api/v1/ops/snapshot and /api/v1/config.
          </Dialog.Description>
          {snap.isFetching || config.isFetching ? <p className="mt-3 text-sm text-text-muted">Checking…</p> : null}
          {snap.error ? <p className="mt-2 text-sm text-danger">{describeError(snap.error)}</p> : null}
          {issues.length === 0 ? (
            <p className="mt-4 flex items-center gap-1.5 text-sm">
              <CircleCheck aria-hidden size={16} className="text-teal" /> Every reported component is OK.
            </p>
          ) : (
            <ul className="mt-3 space-y-1.5">
              {issues.map((i) => (
                <li key={i.name} className="flex flex-wrap items-center gap-2 text-sm">
                  {['OK', 'DEGRADED', 'UNAVAILABLE'].includes(i.status) ? (
                    <StatusPill status={i.status} />
                  ) : (
                    <Pill tone="warn">{i.status}</Pill>
                  )}
                  <span className="font-medium">{i.name}</span>
                  {i.detail ? <span className="text-text-muted">— {i.detail}</span> : null}
                </li>
              ))}
            </ul>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
