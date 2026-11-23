import { useEffect } from 'react';
import { useParams } from 'react-router';
import { Activity, Ban, Box, ChevronRight, Layers, Play, RotateCcw, Square, type LucideIcon } from 'lucide-react';
import { useBurstCurrent, useConfig, useRun, useScenarios } from '../../api/queries';
import type { ScenarioPreset } from '../../api/types';
import { fmtNum, fmtTime } from '../../design/format';
import { useUi } from '../../app/uiStore';
import { cx, GatedButton, JsonViewer, Pill, Skeleton } from '../../design/ui';
import { isBurstPreset, resolvePresets } from './presets';
import { useScenarioActions } from './useScenarioActions';

const icons: Record<string, LucideIcon> = {
  SUCCESS: Activity,
  POLICY_DENIAL: Ban,
  BURST: Layers,
  REDELIVERY: RotateCcw,
  SANDBOX: Box,
};

export function usePresets(): { presets: ScenarioPreset[]; loading: boolean } {
  const scenarios = useScenarios();
  if (scenarios.isPending) return { presets: [], loading: true };
  return { presets: resolvePresets(scenarios.data?.items), loading: false };
}

/** How each preset's run is meant to end, so a presenter knows what to click (see the demo runbook). */
const ENDINGS: Record<string, string> = {
  SUCCESS: 'End it with Approve on the Timeline tab.',
  POLICY_DENIAL: 'End it with Approve: the agent could not act alone, but a person can let the same action through.',
  BURST: 'Nothing to approve: burst runs stop after evidence.',
  REDELIVERY: 'End it with Approve, or let the approval expire.',
  SANDBOX: 'Open the Tool audit tab to see the sandboxed call, then end it with Approve.',
};

export function ScenarioPresets() {
  const { presets, loading } = usePresets();
  const config = useConfig();
  const burst = useBurstCurrent();
  const actions = useScenarioActions();
  const openScenario = useUi((s) => s.openScenario);
  const setOpenScenario = useUi((s) => s.setOpenScenario);
  // Selecting a run unfolds that run's scenario, so the description beside it matches what is on screen.
  const { runId } = useParams();
  const scenario = useRun(runId).data?.scenario;
  useEffect(() => {
    if (scenario && presets.some((p) => p.id === scenario)) setOpenScenario(scenario);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only when the selected run (or its scenario) changes
  }, [runId, scenario]);

  return (
    <section aria-labelledby="scenarios-h" className="space-y-2">
      <h2 id="scenarios-h" className="section-label">
        Scenarios
      </h2>
      {loading ? (
        <>
          <Skeleton className="h-10" label="Loading scenarios" />
          <Skeleton className="h-10" label="Loading scenarios" />
        </>
      ) : (
        <ol className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-surface">
          {presets.map((p, i) => {
            const Icon = icons[p.id] ?? Play;
            const burstPreset = isBurstPreset(p);
            const burstActive = burstPreset && burst.data?.active === true;
            const reason =
              actions.presetDisabledReason(p, config.data) ??
              (burstActive ? 'A burst is already running (BURST_ACTIVE).' : null);
            const pending = actions.isPending(burstPreset ? 'BURST' : `preset:${p.id}`);
            const open = openScenario === p.id;
            const panelId = `scenario-panel-${p.id}`;
            return (
              <li key={p.id} className={cx('ui-transition', open && 'bg-surface-raised/60')}>
                <div className="flex items-center gap-2 px-2.5 py-2">
                  <button
                    type="button"
                    aria-expanded={open}
                    aria-controls={panelId}
                    onClick={() => setOpenScenario(open ? null : p.id)}
                    className="flex min-w-0 flex-1 items-center gap-2 rounded text-left"
                  >
                    <ChevronRight aria-hidden size={14} className={cx('shrink-0 text-text-muted ui-transition', open && 'rotate-90')} />
                    <span
                      aria-hidden
                      className="grid h-5 w-5 shrink-0 place-items-center rounded-full border border-border text-xs font-semibold text-text-muted tabular"
                    >
                      {i + 1}
                    </span>
                    <Icon aria-hidden size={16} className="shrink-0 text-primary" />
                    <span className="min-w-0 text-sm font-semibold leading-snug">
                      {p.title}
                    </span>
                  </button>
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
                    {pending ? 'Sending…' : 'Run'}
                  </GatedButton>
                </div>
                {burstPreset && burstActive && !open ? (
                  <div className="px-2.5 pb-2">
                    <BurstStatusLine />
                  </div>
                ) : null}
                {open ? (
                  <div id={panelId} className="space-y-1.5 px-2.5 pb-2.5 pl-9 text-sm">
                    <p className="text-text-muted">{p.description}</p>
                    {burstPreset ? (
                      <p className="text-text-muted">
                        Server cap: <span className="tabular">{fmtNum(config.data?.limits.burstMaxRuns)}</span> runs
                      </p>
                    ) : null}
                    {burstPreset ? <BurstStatusLine /> : null}
                    {ENDINGS[p.id] ? <p className="text-text">{ENDINGS[p.id]}</p> : null}
                    <p className="text-text-muted">
                      Shortcut: <kbd className="rounded border border-border px-1 font-mono text-xs">Shift+{i + 1}</kbd>
                    </p>
                    <JsonViewer label={burstPreset ? 'Request: POST /api/v1/bursts' : 'Request: POST /api/v1/runs'} value={p.request} />
                  </div>
                ) : null}
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
  if (!burst.data) return <p className="text-sm text-text-muted">Burst status: Unavailable</p>;
  const b = burst.data;
  if (!b.active) return <p className="text-sm text-text-muted">No burst running.</p>;
  return (
    <p className="flex flex-wrap items-center gap-1.5 text-sm" data-testid="burst-status">
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
