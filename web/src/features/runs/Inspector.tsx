import { useEffect } from 'react';
import { Tabs } from 'radix-ui';
import { X } from 'lucide-react';
import { describeError } from '../../api/client';
import { useRun, useRuns } from '../../api/queries';
import type { Run } from '../../api/types';
import { useUi, type InspectorTab } from '../../app/uiStore';
import { fmtNum, fmtPct, fmtTime, humanize } from '../../design/format';
import { RunStatePill } from '../../design/runState';
import { Button, Card, KeyValue, MonoId, Skeleton } from '../../design/ui';
import { PolicyIdentityPanel, ToolAuditPanel } from '../audit/AuditPanels';
import { ElementDetails } from '../topology/ElementDetails';
import { DecisionBar } from './DecisionBar';
import { EvidenceCards } from './EvidenceCards';
import { ModelBadge } from './ModelBadge';
import { TraceLink } from './TraceLink';

const TABS: Array<[InspectorTab, string]> = [
  ['summary', 'Summary'],
  ['evidence', 'Evidence'],
  ['policy', 'Policy'],
  ['audit', 'Tool audit'],
  ['usage', 'Usage'],
];

export function Inspector({ runId, invocationId }: { runId: string | undefined; invocationId: string | undefined }) {
  const tab = useUi((s) => s.inspectorTab);
  const setTab = useUi((s) => s.setInspectorTab);
  const focusNode = useUi((s) => s.focusNode);
  const selectNode = useUi((s) => s.selectNode);
  const run = useRun(runId);

  useEffect(() => {
    if (invocationId) useUi.getState().setInspectorTab('audit');
  }, [invocationId]);

  // The timeline already shows the run's state, evidence and recommendation, so Summary would repeat it: open the
  // tab it does not show instead (Policy for a denial run, Tool audit otherwise). A tab the viewer picked is kept.
  const view = useUi((s) => s.view);
  const scenario = run.data?.scenario;
  useEffect(() => {
    if (view !== 'timeline' || !scenario || invocationId) return;
    if (useUi.getState().inspectorTab === 'summary') setTab(scenario === 'POLICY_DENIAL' ? 'policy' : 'audit');
  }, [view, scenario, runId, invocationId, setTab]);

  return (
    <aside aria-label="Inspector" className="flex h-full min-h-0 flex-col">
      {focusNode ? (
        <div className="border-b border-border p-3">
          <div className="mb-1 flex items-center justify-between">
            <h2 className="section-label">Selected element</h2>
            <Button size="sm" variant="ghost" icon={X} onClick={() => selectNode(null)} aria-label="Clear element selection">
              Clear
            </Button>
          </div>
          <ElementDetails nodeId={focusNode} runId={runId} />
        </div>
      ) : null}
      <Tabs.Root value={tab} onValueChange={(v) => setTab(v as InspectorTab)} className="flex min-h-0 flex-1 flex-col">
        <Tabs.List aria-label="Inspector sections" className="flex gap-0.5 overflow-x-auto border-b border-border px-2 pt-2">
          {TABS.map(([v, label]) => (
            <Tabs.Trigger
              key={v}
              value={v}
              className="ui-transition shrink-0 whitespace-nowrap rounded-t-md border-b-2 border-transparent px-2 py-1.5 text-sm text-text-muted hover:text-text data-[state=active]:border-primary data-[state=active]:font-semibold data-[state=active]:text-text"
            >
              {label}
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <div className="min-h-0 flex-1 overflow-auto p-3">
          {!runId ? (
            <EmptyInspector />
          ) : run.isPending ? (
            <Skeleton className="h-40" label="Loading run" />
          ) : run.isError ? (
            <p role="alert" className="text-sm text-danger">
              {describeError(run.error)}
            </p>
          ) : (
            <>
              <Tabs.Content value="summary">
                <SummaryTab run={run.data} />
              </Tabs.Content>
              <Tabs.Content value="evidence">
                <EvidenceCards runId={run.data.runId} evidence={run.data.evidence} compact />
              </Tabs.Content>
              <Tabs.Content value="policy">
                <PolicyIdentityPanel runId={run.data.runId} />
              </Tabs.Content>
              <Tabs.Content value="audit">
                <ToolAuditPanel runId={run.data.runId} invocationId={invocationId} />
              </Tabs.Content>
              <Tabs.Content value="usage">
                <UsageTab run={run.data} />
              </Tabs.Content>
            </>
          )}
        </div>
      </Tabs.Root>
    </aside>
  );
}

const TAB_HELP: Array<[string, string]> = [
  ['Summary', 'state, classification and trace link'],
  ['Evidence', 'what each evidence agent found, with cited tool calls'],
  ['Policy', 'each tool call\'s verified caller identity and policy decision'],
  ['Tool audit', 'every tool call, how often it was requested and executed'],
  ['Usage', 'model calls, tokens and budget'],
];

/** Nothing selected: say what to do next and what each tab will show. */
function EmptyInspector() {
  const runs = useRuns();
  const none = runs.data?.items.length === 0;
  // Presenter mode hides the left rail (scenarios and Recent runs): point at the guide instead.
  const presenter = useUi((s) => s.presenter);
  return (
    <div className="space-y-3 text-sm" data-testid="inspector-empty">
      <p className="rounded-lg border border-border bg-surface-raised p-3">
        {presenter ? (
          <>
            Press <strong>Run</strong> in the guide above (or{' '}
            <kbd className="rounded border border-border px-1 font-mono text-xs">Shift+1</kbd>…
            <kbd className="rounded border border-border px-1 font-mono text-xs">4</kbd>); the run you start opens here.
            {none ? null : (
              <>
                {' '}
                Step through earlier runs with <kbd className="rounded border border-border px-1 font-mono text-xs">[</kbd> and{' '}
                <kbd className="rounded border border-border px-1 font-mono text-xs">]</kbd>.
              </>
            )}
          </>
        ) : none ? (
          <>
            No runs yet. Run <strong>scenario 1</strong> on the left (or press{' '}
            <kbd className="rounded border border-border px-1 font-mono text-xs">Shift+1</kbd>) to start.
          </>
        ) : (
          <>
            Select a run in <strong>Recent runs</strong>, or step through runs with{' '}
            <kbd className="rounded border border-border px-1 font-mono text-xs">[</kbd> and{' '}
            <kbd className="rounded border border-border px-1 font-mono text-xs">]</kbd>.
          </>
        )}
      </p>
      <div>
        <h3 className="section-label mb-1">For the selected run</h3>
        <dl className="space-y-1">
          {TAB_HELP.map(([tab, what]) => (
            <div key={tab} className="flex gap-2">
              <dt className="w-20 shrink-0 font-semibold">{tab}</dt>
              <dd className="text-text-muted">{what}</dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  );
}

function SummaryTab({ run }: { run: Run }) {
  const c = run.classification;
  return (
    <div className="space-y-3 text-sm">
      <DecisionBar runId={run.runId} compact />
      <div className="flex flex-wrap items-center gap-1.5">
        <RunStatePill state={run.state} outcome={run.outcome} />
        <ModelBadge model={run.model} />
      </div>
      <KeyValue
        items={[
          ['Run ID', <MonoId value={run.runId} label="Run ID" />],
          ['Service', <span className="font-mono">{run.incident.service}</span>],
          ['Scenario', humanize(run.scenario)],
          ['Remediation mode', humanize(run.remediationMode)],
          ['Plan source', humanize(run.planSource)],
          ['Created', fmtTime(run.createdAt)],
          ['Deadline', fmtTime(run.deadline)],
          ['Trace ID', <MonoId value={run.traceId} label="Trace ID" />],
        ]}
      />
      <TraceLink traceId={run.traceId} />
      <Card title="Classification">
        {c ? (
          <>
            <KeyValue
              items={[
                ['Category', humanize(c.category)],
                ['Severity', c.severity],
                ['Confidence', fmtPct(c.confidence)],
              ]}
            />
            {c.rationale ? <p className="mt-2">{c.rationale}</p> : null}
          </>
        ) : (
          <p className="text-text-muted">Not classified yet.</p>
        )}
      </Card>
      <p className="text-text-muted">{run.incident.description}</p>
    </div>
  );
}

function Meter({ label, used, max }: { label: string; used: number | null | undefined; max: number | null | undefined }) {
  const hasBoth = typeof used === 'number' && typeof max === 'number' && max > 0;
  const frac = hasBoth ? Math.min(1, used / max) : 0;
  return (
    <div>
      <div className="flex justify-between text-sm">
        <span className="text-text-muted">{label}</span>
        <span className="tabular">
          {fmtNum(used)} / {fmtNum(max)}
        </span>
      </div>
      {hasBoth ? (
        <div
          role="meter"
          aria-label={label}
          aria-valuemin={0}
          aria-valuemax={max}
          aria-valuenow={used}
          className="mt-1 h-2 overflow-hidden rounded-full bg-surface-raised"
        >
          <div className={frac > 0.8 ? 'h-full bg-warn' : 'h-full bg-primary'} style={{ width: `${frac * 100}%` }} />
        </div>
      ) : null}
    </div>
  );
}

function UsageTab({ run }: { run: Run }) {
  const u = run.usage;
  const b = run.budget;
  const tokens =
    u && typeof u.inputTokens === 'number' && typeof u.outputTokens === 'number' ? u.inputTokens + u.outputTokens : null;
  return (
    <div className="space-y-3 text-sm">
      <Meter label="Model calls" used={u?.modelCalls} max={b?.maxModelCalls} />
      <Meter label="Total tokens" used={tokens} max={b?.maxTotalTokens} />
      <KeyValue
        items={[
          ['Input tokens', fmtNum(u?.inputTokens)],
          ['Output tokens', fmtNum(u?.outputTokens)],
          ['Tool calls', fmtNum(u?.toolCalls)],
          ['Tool calls per task (max)', fmtNum(b?.maxToolCallsPerTask)],
          ['Evidence tasks (max)', fmtNum(b?.maxEvidenceTasks)],
          ['Policy denials', fmtNum(u?.policyDenials)],
        ]}
      />
    </div>
  );
}
