import {
  Ban,
  CircleCheck,
  CircleDashed,
  CircleX,
  Clock,
  Hourglass,
  Loader,
  ShieldExclamationPoint,
  TriangleExclamationPoint,
  type LucideIcon,
} from 'lucide-react';
import type { RunState } from '../api/types';
import { humanize } from './format';
import { Pill, type Tone } from './ui';

export const HAPPY_PATH: RunState[] = [
  'SUBMITTED',
  'TRIAGING',
  'GATHERING_EVIDENCE',
  'RECOMMENDING',
  'AWAITING_APPROVAL',
  'EXECUTING_ACTION',
  'COMPLETED',
];

export const TERMINAL_ALTERNATES: RunState[] = ['FAILED', 'TIMED_OUT', 'NEEDS_REVIEW', 'CANCELLED'];

export const TERMINAL: RunState[] = ['COMPLETED', ...TERMINAL_ALTERNATES];

export function isTerminal(s: string | null | undefined): boolean {
  return !!s && (TERMINAL as string[]).includes(s);
}

const meta: Record<string, { tone: Tone; icon: LucideIcon }> = {
  SUBMITTED: { tone: 'neutral', icon: CircleDashed },
  TRIAGING: { tone: 'info', icon: Loader },
  GATHERING_EVIDENCE: { tone: 'info', icon: Loader },
  RECOMMENDING: { tone: 'info', icon: Loader },
  AWAITING_APPROVAL: { tone: 'purple', icon: Hourglass },
  EXECUTING_ACTION: { tone: 'info', icon: Loader },
  COMPLETED: { tone: 'ok', icon: CircleCheck },
  FAILED: { tone: 'danger', icon: CircleX },
  TIMED_OUT: { tone: 'danger', icon: Clock },
  NEEDS_REVIEW: { tone: 'warn', icon: TriangleExclamationPoint },
  CANCELLED: { tone: 'neutral', icon: Ban },
};

export function stateMeta(state: string): { tone: Tone; icon: LucideIcon; label: string } {
  const m = meta[state] ?? { tone: 'neutral' as Tone, icon: ShieldExclamationPoint };
  return { ...m, label: humanize(state) };
}

export function RunStatePill({ state, outcome }: { state: string; outcome?: string | null }) {
  const m = stateMeta(state);
  return (
    <Pill tone={m.tone} icon={m.icon} testId="run-state">
      {m.label}
      {outcome ? <span className="font-normal"> · {humanize(outcome)}</span> : null}
    </Pill>
  );
}
