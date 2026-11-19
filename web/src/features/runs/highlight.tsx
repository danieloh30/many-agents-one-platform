import type { ReactNode } from 'react';
import type { ProposedAction } from '../../api/types';

export type HighlightKind = 'action' | 'version' | 'metric';

export interface Segment {
  text: string;
  kind: HighlightKind | null;
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Verbs that name the proposed action, by action type. */
const ACTION_WORDS: Record<string, string[]> = {
  ROLLBACK_DEPLOYMENT: ['roll back', 'rollback', 'rolling back', 'rolled back'],
  SET_FEATURE_FLAG: ['disable', 'enable', 'turn off', 'turn on'],
  ESCALATE_TO_HUMAN: ['escalate'],
  OBSERVE: ['observe', 'monitor'],
};

/**
 * Splits model text into plain and highlighted segments: the proposed action and its target, version numbers, and
 * measured values (percentages, durations). Only text is returned; nothing is rendered as HTML.
 */
export function segments(text: string, action?: Pick<ProposedAction, 'actionType' | 'target'> | null): Segment[] {
  const words = [...(action ? (ACTION_WORDS[action.actionType] ?? []) : []), ...(action?.target ? [action.target] : [])];
  const parts = [
    words.length ? `(?<action>\\b(?:${words.map(escape).join('|')})\\b)` : null,
    String.raw`(?<version>\bv?\d+\.\d+\.\d+\b)`,
    String.raw`(?<metric>\b\d+(?:\.\d+)?\s?(?:%|ms\b|s\b|seconds?\b|minutes?\b|hours?\b|h\b))`,
  ].filter(Boolean);
  const re = new RegExp(parts.join('|'), 'gi');
  const out: Segment[] = [];
  let last = 0;
  for (const m of text.matchAll(re)) {
    const i = m.index ?? 0;
    if (i > last) out.push({ text: text.slice(last, i), kind: null });
    const g = m.groups ?? {};
    out.push({ text: m[0], kind: g.action ? 'action' : g.version ? 'version' : 'metric' });
    last = i + m[0].length;
  }
  if (last < text.length) out.push({ text: text.slice(last), kind: null });
  return out;
}

const STYLE: Record<HighlightKind, string> = {
  action: 'rounded tint-primary px-0.5 font-semibold text-primary',
  version: 'rounded bg-surface-raised px-0.5 font-mono font-semibold',
  metric: 'font-semibold tabular text-text',
};

/** Recommendation text with its key terms emphasised (weight and tint, never colour alone). */
export function Highlighted({ text, action }: { text: string; action?: Pick<ProposedAction, 'actionType' | 'target'> | null }): ReactNode {
  return segments(text, action).map((s, i) =>
    s.kind ? (
      <strong key={i} className={STYLE[s.kind]} data-highlight={s.kind}>
        {s.text}
      </strong>
    ) : (
      s.text
    ),
  );
}
