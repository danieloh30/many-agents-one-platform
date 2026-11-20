import { useEffect, useRef } from 'react';
import { ChevronDown, ExternalLink, Gauge, Layers, Network, Server } from 'lucide-react';
import { useLiveStore, EVIDENCE_CONSUMER, EVIDENCE_WORKLOAD } from '../../api/liveStore';
import { useBurstCurrent, useConfig, useOpsSnapshot } from '../../api/queries';
import type { OpsSnapshot } from '../../api/types';
import { useUi } from '../../app/uiStore';
import { describeError } from '../../api/client';
import { ageSeconds, fmtNum, fmtSeconds } from '../../design/format';
import { Button, cx, Pill, Skeleton, StatusPill, Tip, Value } from '../../design/ui';
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
      <h3 className="mb-1 flex items-center gap-1 section-label">
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

/** The two queries tutorial 05 (step 3) asks readers to run: tool calls by decision, and evidence queue depth. */
export const PROMETHEUS_QUERIES = [
  { expr: 'sum by (decision) (increase(maop_tool_invocations_total[15m]))', tab: 'table' },
  { expr: 'maop_queue_pending{consumer="evidence-agents"}', tab: 'graph', range: '15m' },
];

/** Prometheus UI URL that opens with the tutorial's queries already entered (g<n>.expr / tab / range_input). */
export function prometheusQueryUrl(base: string): string {
  const params = new URLSearchParams();
  PROMETHEUS_QUERIES.forEach((q, i) => {
    params.set(`g${i}.expr`, q.expr);
    params.set(`g${i}.tab`, q.tab);
    if (q.range) params.set(`g${i}.range_input`, q.range);
  });
  return `${base.replace(/\/$/, '')}/query?${params.toString()}`;
}

function PrometheusLink() {
  const config = useConfig().data;
  const snap = useOpsSnapshot().data;
  const base = config?.links.prometheus ?? null;
  const url = base ? prometheusQueryUrl(base) : null;
  const prom = platform(snap, (n) => n.includes('prometheus'));
  const reason = !url ? 'Prometheus is not exposed on this deployment.' : prom?.status === 'UNAVAILABLE' ? 'Prometheus is reported unreachable.' : null;
  if (reason || !url) {
    return (
      <Tip content={reason}>
        <span tabIndex={0} aria-disabled="true" className="inline-flex cursor-not-allowed items-center gap-1 text-sm text-text-muted">
          <ExternalLink aria-hidden size={14} /> Prometheus
          <span className="sr-only"> (unavailable: {reason})</span>
        </span>
      </Tip>
    );
  }
  return (
    <a
      href={url}
      target="_blank"
      rel="noreferrer noopener"
      title="Opens Prometheus with tutorial 05's queries: tool calls by decision, and evidence queue depth"
      className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
    >
      <ExternalLink aria-hidden size={14} /> Prometheus
    </a>
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

/** False only on the cluster-free local stack (config.kubernetes.enabled=false); older backends count as true. */
export function useKubernetesEnabled(): boolean {
  return useConfig().data?.kubernetes?.enabled !== false;
}

export function NotOnLocalStack() {
  return (
    <Pill tone="neutral" testId="not-on-local">
      Not on local stack
    </Pill>
  );
}

/** A service that runs as a plain JVM on the local stack: up, but with no Kubernetes replica facts. */
export function LocalProcess() {
  return (
    <Pill tone="neutral" testId="local-process" title="Runs as a local JVM process; replica counts need Kubernetes.">
      Local process
    </Pill>
  );
}

/** One line with the numbers that matter, shown while the strip is folded. Null values read "Unavailable". */
function OpsSummary({ snap, kube, slim = false }: { snap: OpsSnapshot; kube: boolean; slim?: boolean }) {
  const q = queue(snap, EVIDENCE_CONSUMER);
  const w = workload(snap, EVIDENCE_WORKLOAD);
  const ok = snap.platform.filter((p) => p.status === 'OK').length;
  const n = (v: number | null | undefined) => (v === null || v === undefined ? 'Unavailable' : fmtNum(v));
  return (
    <dl className="flex flex-wrap items-center gap-x-5 gap-y-1 text-sm" data-testid="ops-summary">
      <div className="flex items-center gap-1.5">
        <dt className="flex items-center gap-1 text-text-muted">
          <Layers aria-hidden size={14} /> Queue
        </dt>
        <dd className="tabular">
          {n(q?.pending)} pending · {n(q?.inFlight)} in flight
          {!slim && q && q.oldestMessageAgeSeconds !== null ? ` · oldest ${fmtSeconds(q.oldestMessageAgeSeconds)}` : ''}
        </dd>
      </div>
      <div className="flex items-center gap-1.5">
        <dt className="flex items-center gap-1 text-text-muted">
          <Server aria-hidden size={14} /> Evidence agents
        </dt>
        <dd className="tabular">
          {!kube ? (
            <NotOnLocalStack />
          ) : w && w.readyReplicas !== null && w.desiredReplicas !== null ? (
            `${fmtNum(w.readyReplicas)} / ${fmtNum(w.desiredReplicas)} ready`
          ) : (
            <StatusPill status={w?.status ?? null} />
          )}
        </dd>
      </div>
      <div className="flex items-center gap-1.5">
        <dt className="flex items-center gap-1 text-text-muted">
          <Network aria-hidden size={14} /> Platform
        </dt>
        <dd>
          {snap.platform.length === 0 ? (
            'Unavailable'
          ) : ok === snap.platform.length ? (
            <StatusPill status="OK" label={`${ok} of ${snap.platform.length}`} />
          ) : (
            <StatusPill status="DEGRADED" label={`${ok} of ${snap.platform.length} OK`} />
          )}
        </dd>
      </div>
    </dl>
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
  const kube = useKubernetesEnabled();
  const expanded = useUi((s) => s.opsExpanded);
  const setExpanded = useUi((s) => s.setOpsExpanded);
  // Presenter mode keeps the folded strip to one row, so the stage above it keeps its height.
  const slim = useUi((s) => s.presenter) && !expanded;
  // A burst is the moment the tiles matter: open them when one starts (the viewer can fold them again).
  const burstActive = useBurstCurrent().data?.active === true;
  const wasActive = useRef(burstActive);
  useEffect(() => {
    if (burstActive && !wasActive.current) setExpanded(true);
    wasActive.current = burstActive;
  }, [burstActive, setExpanded]);

  return (
    <section aria-labelledby="ops-h" className={cx('@container shrink-0 border-t border-border bg-surface px-4', slim ? 'py-1.5' : 'py-2.5')}>
      <div className={cx('flex flex-wrap items-center gap-x-4 gap-y-2', expanded && 'mb-3')}>
        <h2 id="ops-h" className={slim ? 'sr-only' : 'section-label'}>
          Operations
        </h2>
        {!expanded && snap ? <OpsSummary snap={snap} kube={kube} slim={slim} /> : null}
        {snap ? (
          stale ? (
            <Pill tone="warn" testId="snapshot-age">
              Stale {Math.round(age ?? 0)} s
            </Pill>
          ) : slim ? null : (
            <span className="text-sm text-text-muted tabular" data-testid="snapshot-age">
              Observed {age === null ? 'Unavailable' : `${Math.round(age)} s ago`}
            </span>
          )
        ) : null}
        <span className="ml-auto flex flex-wrap items-center gap-x-3 gap-y-2">
          <BurstStopButton />
          {slim ? null : <GrafanaLink />}
          {slim ? null : <PrometheusLink />}
          <Button
            size="sm"
            variant="ghost"
            icon={ChevronDown}
            aria-expanded={expanded}
            aria-controls="ops-details"
            onClick={() => setExpanded(!expanded)}
            className={cx('[&>svg]:ui-transition', expanded && '[&>svg]:rotate-180')}
          >
            {slim ? <span className="sr-only">Show details</span> : expanded ? 'Hide details' : 'Show details'}
          </Button>
        </span>
      </div>
      {!expanded ? (
        snap ? null : snapQ.isPending ? (
          <Skeleton className="h-6" label="Loading operations snapshot" />
        ) : (
          <p role="status" className="text-sm text-text-muted">
            Operations snapshot Unavailable{snapQ.error ? ` — ${describeError(snapQ.error)}` : ''}.
          </p>
        )
      ) : snapQ.isPending ? (
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
        <div id="ops-details" tabIndex={0} aria-label="Operations details" className="grid max-h-[30vh] gap-2 overflow-y-auto @lg:grid-cols-2 @4xl:grid-cols-4">
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
          <Tile title="Evidence agent replicas" icon={Server} stale={stale}>
            <div className="mb-1 flex flex-wrap items-center gap-1">
              {!kube ? (
                <NotOnLocalStack />
              ) : (
                <StatusPill status={w?.status} />
              )}
              {!kube ? null : w?.scaler ? (
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
