import { Clock } from 'lucide-react';
import type { Run } from '../../api/types';
import { fmtDuration } from '../../design/format';
import { isTerminal } from '../../design/runState';
import { cx } from '../../design/ui';
import { useNow } from '../../design/useNow';

/** Remaining time to the run deadline (or the approval deadline while waiting for a human). */
export function DeadlineBar({ run }: { run: Run }) {
  const now = useNow(1000);
  if (isTerminal(run.state)) {
    return <p className="text-sm text-text-muted">Run finished — deadline no longer applies.</p>;
  }
  const awaiting = run.state === 'AWAITING_APPROVAL';
  const endIso = awaiting ? run.approvalDeadline : run.deadline;
  const label = awaiting ? 'Approval deadline' : 'Run deadline';
  const end = endIso ? Date.parse(endIso) : NaN;
  const start = Date.parse(run.createdAt);
  if (Number.isNaN(end) || Number.isNaN(start)) {
    return <p className="text-sm text-text-muted">{label}: Unavailable</p>;
  }
  // The approval window is 10 min from entering AWAITING_APPROVAL; without that instant we use createdAt.
  const total = Math.max(1, end - start);
  const remaining = Math.max(0, end - now);
  const frac = Math.min(1, remaining / total);
  const low = frac < 0.2;
  return (
    <div>
      <div className="flex items-center justify-between text-sm">
        <span className="flex items-center gap-1 text-text-muted">
          <Clock aria-hidden size={14} /> {label}
        </span>
        <span className={cx('tabular font-semibold', low && 'text-warn')}>
          {remaining === 0 ? 'Deadline passed' : `${fmtDuration(remaining)} left`}
          {low && remaining > 0 ? ' · under 20%' : ''}
        </span>
      </div>
      <div
        role="progressbar"
        aria-label={`${label} remaining`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(frac * 100)}
        className="mt-1 h-2 overflow-hidden rounded-full bg-surface-raised"
      >
        <div className={cx('h-full rounded-full', low ? 'bg-warn' : 'bg-primary')} style={{ width: `${frac * 100}%` }} />
      </div>
    </div>
  );
}
