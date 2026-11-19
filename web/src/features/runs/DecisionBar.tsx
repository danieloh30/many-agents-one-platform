import { useEffect } from 'react';
import { Gavel } from 'lucide-react';
import { useRun } from '../../api/queries';
import { useUi } from '../../app/uiStore';
import { fmtDuration } from '../../design/format';
import { Button, cx } from '../../design/ui';
import { useNow } from '../../design/useNow';

export const DECISION_ANCHOR = 'run-decision';

/**
 * Shown while the selected run waits for a human. "Review & decide" switches to the timeline and scrolls to the
 * Approve / Reject buttons, which otherwise sit below all the evidence.
 */
export function DecisionBar({ runId, compact = false }: { runId: string | undefined; compact?: boolean }) {
  const run = useRun(runId).data;
  const now = useNow(1000);
  const goToDecision = useUi((s) => s.goToDecision);
  if (!run || run.state !== 'AWAITING_APPROVAL' || !run.recommendation) return null;
  const end = run.approvalDeadline ? Date.parse(run.approvalDeadline) : NaN;
  const left = Number.isNaN(end) ? null : Math.max(0, end - now);
  return (
    <div
      role="region"
      aria-label="Decision needed"
      data-testid="decision-bar"
      className={cx(
        'flex flex-wrap items-center gap-2 border-warn tint-warn text-sm',
        compact ? 'rounded-md border px-2.5 py-2' : 'border-b px-3 py-2',
      )}
    >
      <Gavel aria-hidden size={16} className="shrink-0 text-warn" />
      <span className="font-semibold text-warn">Your decision is needed</span>
      <span className="text-text-muted tabular">
        {left === null ? 'approval deadline Unavailable' : left === 0 ? 'approval deadline passed' : `${fmtDuration(left)} left to approve or reject`}
      </span>
      <Button size="sm" variant="primary" className="ml-auto" onClick={goToDecision}>
        Review &amp; decide
      </Button>
    </div>
  );
}

/** Scrolls the timeline to the decision whenever goToDecision() is called (after the view has switched). */
export function useScrollToDecision() {
  const request = useUi((s) => s.decisionRequest);
  useEffect(() => {
    if (!request) return;
    const id = requestAnimationFrame(() => {
      const el = document.getElementById(DECISION_ANCHOR);
      if (!el) return;
      const reduce = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
      // Scroll only the nearest scrolling panel: scrollIntoView would also scroll the page and hide the top bar.
      let box: HTMLElement | null = el.parentElement;
      while (box && !/(auto|scroll)/.test(getComputedStyle(box).overflowY)) box = box.parentElement;
      if (box) {
        const top = box.scrollTop + el.getBoundingClientRect().top - box.getBoundingClientRect().top - box.clientHeight / 3;
        box.scrollTo?.({ top: Math.max(0, top), behavior: reduce ? 'auto' : 'smooth' });
      }
      el.querySelector<HTMLElement>('button')?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(id);
  }, [request]);
}
