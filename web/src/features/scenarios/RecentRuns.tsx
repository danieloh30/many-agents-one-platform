import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ChevronRight, FlaskConical, Layers } from 'lucide-react';
import { useRuns } from '../../api/queries';
import { describeError } from '../../api/client';
import type { RunSummary } from '../../api/types';
import { fmtTime, humanize } from '../../design/format';
import { isTerminal, RunStatePill } from '../../design/runState';
import { cx, Pill, Skeleton } from '../../design/ui';

type Entry = { kind: 'run'; run: RunSummary } | { kind: 'burst'; burstId: string; runs: RunSummary[] };

/** Runs in list order, with each burst's runs folded into one entry where its first run appears. */
export function groupRuns(items: RunSummary[]): Entry[] {
  const out: Entry[] = [];
  const bursts = new Map<string, RunSummary[]>();
  for (const r of items) {
    if (!r.burstId) {
      out.push({ kind: 'run', run: r });
      continue;
    }
    const group = bursts.get(r.burstId);
    if (group) {
      group.push(r);
    } else {
      const runs = [r];
      bursts.set(r.burstId, runs);
      out.push({ kind: 'burst', burstId: r.burstId, runs });
    }
  }
  return out;
}

/** Runs made by ./demo.sh verify carry labels {"source": "demo-verify"}. */
export const isSmokeTestRun = (r: RunSummary) => r.labels?.source === 'demo-verify';

function RunLink({ run, selected, nested }: { run: RunSummary; selected: boolean; nested?: boolean }) {
  // Preset scenarios all share one incident title, so lead with the scenario; show the title only for custom incidents.
  const custom = run.scenario === 'CUSTOM';
  return (
    <Link
      to={`/runs/${run.runId}`}
      aria-current={selected ? 'page' : undefined}
      title={run.title}
      className={cx(
        'ui-transition block rounded-md border px-2 py-1.5 text-sm hover:bg-surface-raised',
        selected ? 'border-primary bg-surface-raised' : 'border-transparent',
      )}
    >
      <span className="flex min-w-0 items-center gap-1.5">
        <span className="truncate font-medium">{nested ? run.title : custom ? run.title : humanize(run.scenario)}</span>
        {isSmokeTestRun(run) ? (
          <Pill tone="neutral" icon={FlaskConical} testId="smoke-test-tag" title="Created by ./demo.sh verify, not by a viewer">
            smoke test
          </Pill>
        ) : null}
      </span>
      <span className="mt-0.5 flex flex-wrap items-center gap-1.5 text-text-muted">
        <RunStatePill state={run.state} outcome={run.outcome} />
        {custom && !nested ? <span>Custom incident</span> : null}
        <span className="tabular">{fmtTime(run.createdAt)}</span>
      </span>
    </Link>
  );
}

function BurstGroup({ runs, selectedRunId }: { runs: RunSummary[]; selectedRunId: string | undefined }) {
  const containsSelected = runs.some((r) => r.runId === selectedRunId);
  const [open, setOpen] = useState(containsSelected);
  const expanded = open || containsSelected;
  const done = runs.filter((r) => isTerminal(r.state)).length;
  const first = runs.reduce((a, b) => (a.createdAt < b.createdAt ? a : b));
  return (
    <div className="rounded-md border border-border">
      <button
        type="button"
        aria-expanded={expanded}
        onClick={() => setOpen(!expanded)}
        className="ui-transition flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-raised"
        data-testid="burst-group"
      >
        <ChevronRight aria-hidden size={14} className={cx('shrink-0 text-text-muted ui-transition', expanded && 'rotate-90')} />
        <Layers aria-hidden size={14} className="shrink-0 text-primary" />
        <span className="min-w-0 flex-1">
          <span className="block font-medium">Burst · {runs.length} runs</span>
          <span className="block text-text-muted tabular">
            {done} of {runs.length} finished · {fmtTime(first.createdAt)}
          </span>
        </span>
      </button>
      {expanded ? (
        <ul className="space-y-0.5 border-t border-border p-1">
          {runs.map((r) => (
            <li key={r.runId}>
              <RunLink run={r} selected={r.runId === selectedRunId} nested />
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

export function RecentRuns() {
  const runs = useRuns();
  const { runId } = useParams();
  return (
    <section aria-labelledby="recent-h" className="space-y-2">
      <h2 id="recent-h" className="section-label">
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
          {groupRuns(runs.data.items).map((e) =>
            e.kind === 'run' ? (
              <li key={e.run.runId}>
                <RunLink run={e.run} selected={e.run.runId === runId} />
              </li>
            ) : (
              <li key={e.burstId}>
                <BurstGroup runs={e.runs} selectedRunId={runId} />
              </li>
            ),
          )}
        </ul>
      )}
    </section>
  );
}
