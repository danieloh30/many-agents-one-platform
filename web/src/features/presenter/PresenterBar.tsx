import { useState } from 'react';
import { ChevronDown, ChevronRight, Circle, CircleCheck, ClipboardCheck, Crosshair, Layers, Play } from 'lucide-react';
import { useLiveStore } from '../../api/liveStore';
import { useBurstCurrent, useConfig, useRun } from '../../api/queries';
import { useUi } from '../../app/uiStore';
import { fmtNum } from '../../design/format';
import { Button, cx, GatedButton, Pill } from '../../design/ui';
import { BurstStatusLine, BurstStopButton, usePresets } from '../scenarios/ScenarioPresets';
import { useScenarioActions } from '../scenarios/useScenarioActions';
import { checklist, GUIDE_STEPS, type GuideStep } from './checklist';
import { PreflightPanel } from './PreflightPanel';
import { ResetControl } from './ResetControl';

function StepCard({ step }: { step: GuideStep }) {
  const { presets } = usePresets();
  const config = useConfig();
  const actions = useScenarioActions();
  const burst = useBurstCurrent();
  const events = useLiveStore((s) => s.events);
  const history = useLiveStore((s) => s.history);
  const runId = useUi((s) => s.lastRunByScenario[step.id]) ?? null;
  const burstStartedAt = useUi((s) => s.burstStartedAt);
  const focusOn = useUi((s) => s.focusOn);
  const run = useRun(step.id === 'BURST' ? undefined : (runId ?? undefined));
  const preset = presets.find((p) => p.id === step.id);
  const items = checklist(step.id, { events, runId, run: run.data, history, burstStartedAt });
  const done = items.every((i) => i.done);
  const isBurst = step.id === 'BURST';
  const reason = !preset
    ? 'Preset not available.'
    : (actions.presetDisabledReason(preset, config.data) ?? (isBurst && burst.data?.active ? 'A burst is already running.' : null));
  const pending = actions.isPending(isBurst ? 'BURST' : `preset:${step.id}`);

  return (
    <li className={cx('min-w-0 rounded-lg border bg-surface p-2.5', done ? 'border-teal/60' : 'border-border')}>
      <div className="flex items-center gap-2">
        <span className="grid h-6 w-6 place-items-center rounded-full bg-surface-raised text-sm font-bold tabular">{step.number}</span>
        <h3 className="font-semibold">
          {step.title}
          {step.optional ? <span className="ml-1 text-sm font-normal text-text-muted">(optional)</span> : null}
        </h3>
        {isBurst && burst.data?.active ? (
          <Pill tone="info" icon={Layers} className="ml-auto">
            {fmtNum(burst.data.completed)} / {fmtNum(burst.data.runs)}
          </Pill>
        ) : null}
      </div>
      {isBurst ? <BurstStatusLine /> : null}
      <ul className="mt-1.5 space-y-0.5 text-sm" aria-label={`Expected observations for step ${step.number}`}>
        {items.map((i) => (
          <li key={i.id} className={cx('flex items-start gap-1.5', !i.done && 'text-text-muted')}>
            {i.done ? (
              <CircleCheck aria-hidden size={15} className="mt-0.5 shrink-0 text-teal" />
            ) : (
              <Circle aria-hidden size={15} className="mt-0.5 shrink-0" />
            )}
            <span>
              {i.label}
              <span className="sr-only">{i.done ? ' — observed' : ' — not yet observed'}</span>
            </span>
          </li>
        ))}
      </ul>
      <div className="mt-2 flex flex-wrap gap-1.5">
        <GatedButton
          size="sm"
          variant="primary"
          icon={isBurst ? Layers : Play}
          disabledReason={reason}
          disabled={pending}
          onClick={() => preset && void actions.runPreset(preset)}
        >
          {isBurst ? 'Start burst' : 'Run'}
        </GatedButton>
        {isBurst ? <BurstStopButton /> : null}
        <Button size="sm" variant="ghost" icon={Crosshair} onClick={() => focusOn(step.focus)}>
          Focus
        </Button>
      </div>
    </li>
  );
}

export function PresenterBar() {
  const [preflight, setPreflight] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  return (
    <section aria-labelledby="guide-h" className="@container shrink-0 border-b border-border bg-bg p-2">
      <div className="mb-1.5 flex items-center gap-2">
        <Button
          size="sm"
          variant="ghost"
          icon={collapsed ? ChevronRight : ChevronDown}
          onClick={() => setCollapsed((c) => !c)}
          aria-expanded={!collapsed}
          aria-controls="guide-steps"
          aria-label={collapsed ? 'Expand guide' : 'Collapse guide'}
        />
        <h2 id="guide-h" className="section-label">
          Guide
        </h2>
        <span className="hidden text-sm text-text-muted @3xl:inline">Checklist items tick only when the matching real event arrives.</span>
        <span className="ml-auto flex gap-1.5">
          <Button size="sm" icon={ClipboardCheck} onClick={() => setPreflight(true)}>
            Preflight
          </Button>
          <ResetControl />
        </span>
      </div>
      <ol id="guide-steps" hidden={collapsed} className="grid gap-2 @xl:grid-cols-2 @6xl:grid-cols-4">
        {GUIDE_STEPS.map((s) => (
          <StepCard key={s.id} step={s} />
        ))}
      </ol>
      <PreflightPanel open={preflight} onOpenChange={setPreflight} />
    </section>
  );
}
