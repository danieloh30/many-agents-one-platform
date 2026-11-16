import { Link, useParams } from 'react-router';
import { useRuns } from '../../api/queries';
import { describeError } from '../../api/client';
import { fmtTime, humanize } from '../../design/format';
import { RunStatePill } from '../../design/runState';
import { cx, Skeleton } from '../../design/ui';

export function RecentRuns() {
  const runs = useRuns();
  const { runId } = useParams();
  return (
    <section aria-labelledby="recent-h" className="space-y-2">
      <h2 id="recent-h" className="text-sm font-semibold uppercase tracking-wide text-text-muted">
        Recent runs
      </h2>
      {runs.isPending ? (
        <div className="space-y-2">
          <Skeleton className="h-12" label="Loading runs" />
          <Skeleton className="h-12" label="Loading runs" />
        </div>
      ) : runs.isError ? (
        <p className="text-sm text-danger">{describeError(runs.error)}</p>
      ) : runs.data.items.length === 0 ? (
        <p className="text-sm text-text-muted">No runs yet. Run a scenario to start.</p>
      ) : (
        <ul className="space-y-1">
          {runs.data.items.map((r) => (
            <li key={r.runId}>
              <Link
                to={`/runs/${r.runId}`}
                aria-current={r.runId === runId ? 'page' : undefined}
                className={cx(
                  'ui-transition block rounded-md border px-2 py-1.5 text-sm hover:bg-surface-raised',
                  r.runId === runId ? 'border-primary bg-surface-raised' : 'border-transparent',
                )}
              >
                <span className="block truncate font-medium">{r.title}</span>
                <span className="mt-0.5 flex flex-wrap items-center gap-1.5 text-text-muted">
                  <RunStatePill state={r.state} />
                  <span>{humanize(r.scenario)}</span>
                  <span className="tabular">{fmtTime(r.createdAt)}</span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
