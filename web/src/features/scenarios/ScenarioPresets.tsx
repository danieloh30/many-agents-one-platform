import { Activity, Ban, Layers, Play, RotateCcw, Square, type LucideIcon } from 'lucide-react';
import { useBurstCurrent, useConfig, useScenarios } from '../../api/queries';
import type { ScenarioPreset } from '../../api/types';
import { fmtNum, fmtTime } from '../../design/format';
import { GatedButton, JsonViewer, Pill, Skeleton } from '../../design/ui';
import { isBurstPreset, resolvePresets } from './presets';
import { useScenarioActions } from './useScenarioActions';

const icons: Record<string, LucideIcon> = {
  SUCCESS: Activity,
  POLICY_DENIAL: Ban,
  BURST: Layers,
  REDELIVERY: RotateCcw,
};

export function usePresets(): { presets: ScenarioPreset[]; loading: boolean } {
  const scenarios = useScenarios();
  if (scenarios.isPending) return { presets: [], loading: true };
  return { presets: resolvePresets(scenarios.data?.items), loading: false };
}

export function ScenarioPresets() {
  const { presets, loading } = usePresets();
  const config = useConfig();
  const burst = useBurstCurrent();
  const actions = useScenarioActions();

  return (
    <section aria-labelledby="scenarios-h" className="space-y-2">
      <h2 id="scenarios-h" className="text-sm font-semibold uppercase tracking-wide text-text-muted">
        Scenarios
      </h2>
      {loading ? (
        <>
          <Skeleton className="h-20" label="Loading scenarios" />
          <Skeleton className="h-20" label="Loading scenarios" />
        </>
      ) : (
        <ol className="space-y-2">
          {presets.map((p, i) => {
            const Icon = icons[p.id] ?? Play;
            const burstPreset = isBurstPreset(p);
            const burstActive = burstPreset && burst.data?.active === true;
            const reason =
              actions.presetDisabledReason(p, config.data) ??
              (burstActive ? 'A burst is already running (BURST_ACTIVE).' : null);
            const pending = actions.isPending(burstPreset ? 'BURST' : `preset:${p.id}`);
            return (
              <li key={p.id} className="rounded-lg border border-border bg-surface p-3">
                <div className="flex items-start gap-2">
                  <Icon aria-hidden size={18} className="mt-0.5 shrink-0 text-primary" />
                  <div className="min-w-0 flex-1">
                    <h3 className="font-semibold leading-tight">{p.title}</h3>
                    <p className="mt-0.5 text-sm text-text-muted">{p.description}</p>
                    {burstPreset ? (
                      <p className="mt-1 text-sm text-text-muted">
                        Server cap: <span className="tabular">{fmtNum(config.data?.limits.burstMaxRuns)}</span> runs
                      </p>
                    ) : null}
                    {burstPreset ? <BurstStatusLine /> : null}
                  </div>
                </div>
                <div className="mt-2 flex items-center justify-between gap-2">
                  <span className="text-sm text-text-muted">Shift+{i + 1}</span>
                  <GatedButton
                    variant="primary"
                    size="sm"
                    icon={burstPreset ? Layers : Play}
                    disabledReason={reason}
                    disabled={pending}
                    aria-busy={pending}
                    onClick={() => void actions.runPreset(p)}
                    aria-label={`Run scenario: ${p.title}`}
                  >
                    {pending ? 'Sending…' : burstPreset ? 'Start burst' : 'Run'}
                  </GatedButton>
                </div>
                <div className="mt-2">
                  <JsonViewer label={burstPreset ? 'Request: POST /api/v1/bursts' : 'Request: POST /api/v1/runs'} value={p.request} />
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}

/** State of the bounded burst as reported by GET /api/v1/bursts/current (and burst.changed events). */
export function BurstStatusLine() {
  const burst = useBurstCurrent();
  if (burst.isPending) return null;
  if (!burst.data) return <p className="mt-1 text-sm text-text-muted">Burst status: Unavailable</p>;
  const b = burst.data;
  if (!b.active) return <p className="mt-1 text-sm text-text-muted">No burst running.</p>;
  return (
    <p className="mt-1 flex flex-wrap items-center gap-1.5 text-sm" data-testid="burst-status">
      <Pill tone="info" icon={Activity}>
        Burst active
      </Pill>
      <span className="tabular">
        {fmtNum(b.completed)} / {fmtNum(b.runs)} runs completed
      </span>
      {b.startedAt ? <span className="text-text-muted tabular">since {fmtTime(b.startedAt)}</span> : null}
    </p>
  );
}

export function BurstStopButton() {
  const burst = useBurstCurrent();
  const actions = useScenarioActions();
  if (!burst.data?.active) return null;
  return (
    <GatedButton
      variant="danger"
      size="sm"
      icon={Square}
      disabledReason={actions.disabledReason}
      disabled={actions.isPending('BURST_STOP')}
      onClick={() => void actions.stopBurst()}
    >
      Stop burst
    </GatedButton>
  );
}
