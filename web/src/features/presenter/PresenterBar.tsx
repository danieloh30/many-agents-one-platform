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

/** Checklist items for one guide step, from real events and REST state only. */
function useStepItems(step: GuideStep) {
  const events = useLiveStore((s) => s.events);
  const history = useLiveStore((s) => s.history);
  const runId = useUi((s) => s.lastRunByScenario[step.id]) ?? null;
  const burstStartedAt = useUi((s) => s.burstStartedAt);
  const run = useRun(step.id === 'BURST' ? undefined : (runId ?? undefined));
  return checklist(step.id, { events, runId, run: run.data, history, burstStartedAt });
}

/** A step's chip in the guide's header: number, title and whether all its observations have been seen. */
function StepChip({ step, active, onSelect }: { step: GuideStep; active: boolean; onSelect: () => void }) {
  const items = useStepItems(step);
  const done = items.length > 0 && items.every((i) => i.done);
  const seen = items.filter((i) => i.done).length;
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={active}
      data-testid={`guide-chip-${step.id}`}
      className={cx(
        'ui-transition inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-sm',
        active ? 'border-primary bg-surface-raised font-semibold text-text' : 'border-border text-text-muted hover:text-text',
      )}
    >
      {done ? (
        <CircleCheck aria-hidden size={14} className="text-teal" />
      ) : (
        <span aria-hidden className="grid h-4 w-4 place-items-center rounded-full border border-current text-[10px] font-bold tabular">
          {step.number}
        </span>
      )}
      {step.title}
      <span className="text-xs font-normal text-text-muted tabular">
        {seen}/{items.length}
      </span>
      <span className="sr-only">{done ? ', all observed' : `, ${seen} of ${items.length} observed`}</span>
    </button>
  );
}

function StepCard({ step }: { step: GuideStep }) {
  const { presets } = usePresets();
  const config = useConfig();
  const actions = useScenarioActions();
  const burst = useBurstCurrent();
  const focusOn = useUi((s) => s.focusOn);
  const items = useStepItems(step);
  const preset = presets.find((p) => p.id === step.id);
  const done = items.every((i) => i.done);
  const isBurst = step.id === 'BURST';
  const reason = !preset
    ? 'Preset not available.'
    : (actions.presetDisabledReason(preset, config.data) ?? (isBurst && burst.data?.active ? 'A burst is already running.' : null));
  const pending = actions.isPending(isBurst ? 'BURST' : `preset:${step.id}`);

  return (
    <div
      data-testid="guide-step"
      className={cx('flex flex-wrap items-start gap-x-4 gap-y-2 rounded-lg border bg-surface p-2.5', done ? 'border-teal/60' : 'border-border')}
    >
      <div className="flex shrink-0 flex-col gap-1.5">
        <div className="flex items-center gap-2">
          <span className="grid h-6 w-6 place-items-center rounded-full bg-surface-raised text-sm font-bold tabular">{step.number}</span>
          <h3 className="font-semibold">
            {step.title}
            {step.optional ? <span className="ml-1 text-sm font-normal text-text-muted">(optional)</span> : null}
          </h3>
          {isBurst && burst.data?.active ? (
            <Pill tone="info" icon={Layers}>
              {fmtNum(burst.data.completed)} / {fmtNum(burst.data.runs)}
            </Pill>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-1.5">
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
        {isBurst ? <BurstStatusLine /> : null}
      </div>
      <ul
        className="grid min-w-0 flex-1 gap-x-4 gap-y-0.5 text-sm @3xl:grid-cols-2"
        aria-label={`Expected observations for step ${step.number}`}
      >
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
    </div>
  );
}

export function PresenterBar() {
  const [preflight, setPreflight] = useState(false);
  const folded = useUi((s) => s.guideFolded);
  const toggleGuide = useUi((s) => s.toggleGuide);
  const active = useUi((s) => s.guideStep);
  const setActive = useUi((s) => s.setGuideStep);
  const step = GUIDE_STEPS.find((g) => g.id === active) ?? GUIDE_STEPS[0]!;
  return (
    <section aria-labelledby="guide-h" className="@container shrink-0 border-b border-border bg-bg p-2">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          variant="ghost"
          icon={folded ? ChevronRight : ChevronDown}
          onClick={toggleGuide}
          aria-expanded={!folded}
          aria-controls="guide-steps"
          aria-label={folded ? 'Unfold guide (G)' : 'Fold guide (G)'}
          title={folded ? 'Unfold guide (G)' : 'Fold guide (G)'}
        />
        <h2 id="guide-h" className="section-label">
          Guide
        </h2>
        <div role="group" aria-label="Guide steps" className="flex flex-wrap gap-1">
          {GUIDE_STEPS.map((g) => (
            <StepChip key={g.id} step={g} active={g.id === step.id} onSelect={() => setActive(g.id)} />
          ))}
        </div>
        <span className="ml-auto flex gap-1.5">
          <Button size="sm" icon={ClipboardCheck} onClick={() => setPreflight(true)}>
            Preflight
          </Button>
          <ResetControl />
        </span>
      </div>
      <div id="guide-steps" hidden={folded} className="mt-2">
        <StepCard step={step} />
        <p className="mt-1 hidden text-xs text-text-muted @3xl:block">Checklist items tick only when the matching real event arrives.</p>
      </div>
      <PreflightPanel open={preflight} onOpenChange={setPreflight} />
    </section>
  );
}
