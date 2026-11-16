import { ExternalLink, Gauge, Layers, Network, Server } from 'lucide-react';
import { useLiveStore, EVIDENCE_CONSUMER, EVIDENCE_WORKLOAD } from '../../api/liveStore';
import { useConfig, useOpsSnapshot } from '../../api/queries';
import { describeError } from '../../api/client';
import { ageSeconds, fmtNum, fmtSeconds } from '../../design/format';
import { cx, Pill, Skeleton, StatusPill, Tip, Value } from '../../design/ui';
import { useNow } from '../../design/useNow';
import { BurstStopButton } from '../scenarios/ScenarioPresets';
import { Sparkline, StepChart } from './Charts';
import { componentLabel, platform, podLabel, queue, workload } from './selectors';

export const STALE_AFTER_S = 10;

function Tile({ title, icon: Icon, stale, children }: { title: string; icon: typeof Gauge; stale: boolean; children: React.ReactNode }) {
  return (
    <section
      aria-label={title}
      className={cx('ui-transition min-w-0 rounded-lg border border-border bg-surface p-2.5', stale && 'opacity-60 grayscale')}
    >
      <h3 className="mb-1 flex items-center gap-1 text-sm font-semibold uppercase tracking-wide text-text-muted">
        <Icon aria-hidden size={14} /> {title}
      </h3>
      {children}
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="min-w-0">
      <div className="text-sm text-text-muted">{label}</div>
      <div className="text-xl font-semibold">
        <Value value={value} />
      </div>
    </div>
  );
}

function GrafanaLink() {
  const config = useConfig().data;
  const snap = useOpsSnapshot().data;
  const url = config?.links.grafanaDashboard ?? config?.links.grafana ?? null;
  const grafana = platform(snap, (n) => n.includes('grafana'));
  const reason = !url ? 'Grafana is not exposed on this deployment.' : grafana?.status === 'UNAVAILABLE' ? 'Grafana is reported unreachable.' : null;
  if (reason || !url) {
    return (
      <Tip content={reason}>
        <span tabIndex={0} aria-disabled="true" className="inline-flex cursor-not-allowed items-center gap-1 text-sm text-text-muted">
          <ExternalLink aria-hidden size={14} /> Grafana dashboard
          <span className="sr-only"> (unavailable: {reason})</span>
        </span>
      </Tip>
    );
  }
  return (
    <a href={url} target="_blank" rel="noreferrer noopener" className="inline-flex items-center gap-1 text-sm text-primary hover:underline">
      <ExternalLink aria-hidden size={14} /> Grafana dashboard
    </a>
  );
}

export function OpsStrip() {
  const snapQ = useOpsSnapshot();
  const history = useLiveStore((s) => s.history);
  const now = useNow(1000);
  const snap = snapQ.data;
  const age = ageSeconds(snap?.observedAt, now);
  const stale = age !== null && age > STALE_AFTER_S;
  const q = queue(snap, EVIDENCE_CONSUMER);
  const w = workload(snap, EVIDENCE_WORKLOAD);

  return (
    <section aria-labelledby="ops-h" className="@container shrink-0 border-t border-border bg-bg p-2">
      <div className="mb-1.5 flex flex-wrap items-center gap-2">
        <h2 id="ops-h" className="text-sm font-semibold uppercase tracking-wide text-text-muted">
          Operations
        </h2>
        {snap ? (
          stale ? (
            <Pill tone="warn" testId="snapshot-age">
              Stale {Math.round(age ?? 0)} s
            </Pill>
          ) : (
            <span className="text-sm text-text-muted tabular" data-testid="snapshot-age">
              Observed {age === null ? 'Unavailable' : `${Math.round(age)} s ago`}
            </span>
          )
        ) : null}
        <span className="ml-auto flex items-center gap-3">
          <BurstStopButton />
          <GrafanaLink />
        </span>
      </div>
      {snapQ.isPending ? (
        <div className="grid gap-2 @lg:grid-cols-2 @4xl:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-28" label="Loading operations snapshot" />
          ))}
        </div>
      ) : !snap ? (
        <p role="status" className="text-sm text-text-muted">
          Operations snapshot Unavailable{snapQ.error ? ` — ${describeError(snapQ.error)}` : ''}.
        </p>
      ) : (
        <div className="grid gap-2 @lg:grid-cols-2 @4xl:grid-cols-4">
          <Tile title="Evidence queue" icon={Layers} stale={stale}>
            <div className="mb-1">
              <StatusPill status={q?.status} />
            </div>
            <div className="grid grid-cols-3 gap-2">
              <Metric label="Pending" value={q ? (q.pending === null ? null : fmtNum(q.pending)) : null} />
              <Metric label="In flight" value={q ? (q.inFlight === null ? null : fmtNum(q.inFlight)) : null} />
              <Metric
                label="Oldest age"
                value={q ? (q.oldestMessageAgeSeconds === null ? null : fmtSeconds(q.oldestMessageAgeSeconds)) : null}
              />
            </div>
            <Sparkline points={history} label="Evidence queue pending" />
          </Tile>
          <Tile title="Evidence worker replicas" icon={Server} stale={stale}>
            <div className="mb-1 flex flex-wrap items-center gap-1">
              <StatusPill status={w?.status} />
              {w?.scaler ? (
                <Pill tone={w.scaler.active === true ? 'info' : 'neutral'}>
                  {w.scaler.kind ?? 'Scaler'}: {w.scaler.active === null ? 'Unavailable' : w.scaler.active ? 'active' : 'idle'}
                </Pill>
              ) : (
                <Pill tone="neutral">Scaler: Unavailable</Pill>
              )}
            </div>
            <div className="grid grid-cols-2 gap-2">
              <Metric
                label="Ready / desired"
                value={w && w.readyReplicas !== null && w.desiredReplicas !== null ? `${fmtNum(w.readyReplicas)} / ${fmtNum(w.desiredReplicas)}` : null}
              />
              <Metric
                label="Min – max"
                value={w && w.minReplicas !== null && w.maxReplicas !== null ? `${fmtNum(w.minReplicas)} – ${fmtNum(w.maxReplicas)}` : null}
              />
            </div>
            <StepChart points={history} />
          </Tile>
          <Tile title="Worker pods" icon={Gauge} stale={stale}>
            {!w || w.pods === null ? (
              <Value value={null} reason="Pod list not reported" />
            ) : w.pods.length === 0 ? (
              <p className="text-sm text-text-muted">No pods reported.</p>
            ) : (
              <ul className="max-h-36 space-y-0.5 overflow-auto text-sm">
                {w.pods.map((p) => (
                  <li key={p.name} className="flex items-center justify-between gap-2">
                    <span className="truncate font-mono" title={p.name}>
                      {p.name}
                    </span>
                    <span className="flex shrink-0 items-center gap-1.5">
                      <span>{podLabel(p)}</span>
                      <span className="text-text-muted">
                        tasks <Value value={p.activeTasks === null ? null : fmtNum(p.activeTasks)} reason="No heartbeat within 10 s" />
                      </span>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Tile>
          <Tile title="Platform" icon={Network} stale={stale}>
            {snap.platform.length === 0 ? (
              <Value value={null} reason="No platform components reported" />
            ) : (
              <ul className="flex flex-wrap gap-1">
                {snap.platform.map((p) => (
                  <li key={p.component} title={p.detail ?? undefined}>
                    <StatusPill status={p.status} label={componentLabel(p.component)} />
                  </li>
                ))}
              </ul>
            )}
          </Tile>
        </div>
      )}
    </section>
  );
}
