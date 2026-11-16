import { Check } from 'lucide-react';
import type { Run, RunTimelineEntry } from '../../api/types';
import { fmtDuration, fmtTime } from '../../design/format';
import { HAPPY_PATH, isTerminal, stateMeta, TERMINAL_ALTERNATES } from '../../design/runState';
import { cx } from '../../design/ui';
import { useNow } from '../../design/useNow';

interface Step {
  state: string;
  at: number | null;
  durationMs: number | null;
  status: 'done' | 'current' | 'todo' | 'skipped';
}

export function buildSteps(run: Run, timeline: RunTimelineEntry[], now: number): Step[] {
  const entered = new Map<string, number>();
  const changes = timeline
    .filter((e) => e.kind === 'STATE_CHANGED' && e.to)
    .sort((a, b) => a.seq - b.seq);
  for (const c of changes) {
    const t = Date.parse(c.at);
    if (!Number.isNaN(t) && !entered.has(c.to!)) entered.set(c.to!, t);
  }
  if (!entered.has('SUBMITTED')) {
    const t = Date.parse(run.createdAt);
    if (!Number.isNaN(t)) entered.set('SUBMITTED', t);
  }
  const order = [...HAPPY_PATH];
  const alternate = TERMINAL_ALTERNATES.find((s) => s === run.state || entered.has(s));
  if (alternate) order.push(alternate);
  const enteredTimes = [...entered.values()].sort((a, b) => a - b);
  const currentIdx = order.indexOf(run.state);
  return order.map((state, i) => {
    const at = entered.get(state) ?? null;
    const next = at !== null ? enteredTimes.find((t) => t > at) : undefined;
    let status: Step['status'] = 'todo';
    if (state === run.state) status = isTerminal(state) ? 'done' : 'current';
    else if (at !== null) status = 'done';
    else if (i < currentIdx) status = 'skipped';
    else if (alternate && state !== alternate) status = 'skipped';
    const durationMs =
      at === null ? null : next !== undefined ? next - at : status === 'current' ? now - at : null;
    return { state, at, durationMs, status };
  });
}

export function StateRail({ run, timeline }: { run: Run; timeline: RunTimelineEntry[] }) {
  const now = useNow(1000);
  const steps = buildSteps(run, timeline, now);
  return (
    <ol aria-label="Run state" className="flex flex-wrap gap-1">
      {steps.map((s) => {
        const m = stateMeta(s.state);
        const Icon = s.status === 'done' ? Check : m.icon;
        const alt = (TERMINAL_ALTERNATES as string[]).includes(s.state);
        return (
          <li
            key={s.state}
            aria-current={s.status === 'current' ? 'step' : undefined}
            className={cx(
              'min-w-[7.5rem] flex-1 rounded-md border px-2 py-1.5 text-sm',
              s.status === 'current' && 'border-primary tint-primary',
              s.status === 'done' && !alt && 'border-teal/50',
              s.status === 'done' && alt && 'border-danger tint-danger',
              (s.status === 'todo' || s.status === 'skipped') && 'border-border opacity-70',
            )}
          >
            <span className="flex items-center gap-1 font-semibold">
              <Icon aria-hidden size={14} className={s.status === 'done' && !alt ? 'text-teal' : undefined} />
              {m.label}
            </span>
            <span className="block text-text-muted tabular">
              {s.at !== null ? fmtTime(new Date(s.at).toISOString()) : s.status === 'skipped' ? 'Skipped' : 'Not reached'}
              {s.durationMs !== null ? ` · ${fmtDuration(s.durationMs)}` : ''}
            </span>
            <span className="sr-only">
              {s.status === 'current' ? 'current step' : s.status === 'done' ? 'completed' : s.status}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
