import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Ban, Check, ShieldCheck, X } from 'lucide-react';
import { api, ApiError, describeError, queryKeys } from '../../api/client';
import type { Run } from '../../api/types';
import { useNotify } from '../../app/notify';
import { useCanMutate, useSession } from '../../app/session';
import { ConfirmDialog } from '../../design/ConfirmDialog';
import { fmtPct, fmtTime, humanize } from '../../design/format';
import { Card, GatedButton, KeyValue, MonoId, Pill } from '../../design/ui';
import { DECISION_ANCHOR } from './DecisionBar';
import { openEvidence } from './EvidenceCards';
import { ModelBadge } from './ModelBadge';
import { Highlighted } from './highlight';

export function RecommendationCard({ run }: { run: Run }) {
  const rec = run.recommendation;
  if (!rec) {
    return (
      <Card title="Recommendation">
        <p className="text-sm text-text-muted">No recommendation yet.</p>
      </Card>
    );
  }
  const a = rec.proposedAction;
  const params = Object.entries(a.parameters ?? {});
  return (
    <Card
      title="Recommendation"
      actions={
        <span className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
          <ModelBadge model={run.model} compact />
          <span className="text-sm text-text-muted">
            Confidence <span className="tabular font-semibold text-text">{fmtPct(rec.confidence)}</span>
          </span>
        </span>
      }
    >
      <p className="text-lg font-semibold" data-testid="rec-summary">
        <Highlighted text={rec.summary} action={a} />
      </p>
      <p className="mt-1 text-sm leading-relaxed" data-testid="rec-explanation">
        <Highlighted text={rec.explanation} action={a} />
      </p>

      <p className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm" data-testid="action-line">
        <span className="font-semibold text-text-muted">Proposed action</span>
        <span className="rounded border border-border bg-surface-raised px-1.5 font-mono">{a.actionType}</span>
        <span className="font-mono">{a.target}</span>
        {params.length > 0 ? <span className="font-mono">{params.map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(', ')}</span> : null}
        <span className="text-text-muted">{a.sideEffect ? '· simulated only' : '· no side effect'}</span>
      </p>
      <details className="mt-1 text-sm">
        <summary className="cursor-pointer text-text-muted">Action details</summary>
      <table className="mt-1 w-full text-sm">
        <caption className="sr-only">Proposed action</caption>
        <tbody className="[&_th]:w-36 [&_th]:py-0.5 [&_th]:pr-2 [&_th]:text-left [&_th]:font-normal [&_th]:text-text-muted [&_td]:py-0.5">
          <tr>
            <th scope="row">Type</th>
            <td className="font-mono">{a.actionType}</td>
          </tr>
          <tr>
            <th scope="row">Target</th>
            <td className="font-mono">{a.target}</td>
          </tr>
          <tr>
            <th scope="row">Parameters</th>
            <td className="font-mono">
              {params.length === 0 ? 'None' : params.map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(', ')}
            </td>
          </tr>
          <tr>
            <th scope="row">Side effect</th>
            <td>{a.sideEffect ? 'Yes — simulated only' : 'No'}</td>
          </tr>
          <tr>
            <th scope="row">Action digest</th>
            <td>
              <MonoId value={a.actionDigest} label="Action digest" max={30} />
            </td>
          </tr>
        </tbody>
      </table>

      </details>

      {rec.citations.length > 0 ? (
        <div className="mt-3">
          <h4 className="text-sm font-semibold text-text-muted">Citations</h4>
          <ul className="mt-1 flex flex-wrap gap-1">
            {rec.citations.map((c) => {
              const ev = run.evidence.find((e) => e.evidenceId === c);
              return (
                <li key={c}>
                  <a
                    href={`#evidence-${c}`}
                    onClick={() => openEvidence(c)}
                    className="inline-flex rounded-full border border-primary/50 tint-primary px-2 py-0.5 text-sm text-primary hover:underline"
                  >
                    {ev ? humanize(ev.topic) : c.slice(0, 8)}
                  </a>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}

      {rec.risks.length > 0 ? (
        <div className="mt-3">
          <h4 className="text-sm font-semibold text-text-muted">Risks</h4>
          <ul className="mt-1 list-disc pl-5 text-sm">
            {rec.risks.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {run.executionAttempt ? (
        <div className="mt-3 rounded-md border border-danger/50 tint-danger p-2 text-sm">
          <p className="flex items-center gap-1.5 font-semibold text-danger">
            <Ban aria-hidden size={14} /> Unapproved execution attempt: {run.executionAttempt.status}
          </p>
          <p className="mt-0.5">
            Reasons: <span className="font-mono">{run.executionAttempt.reasons.join(', ') || 'none reported'}</span>
          </p>
        </div>
      ) : null}
      {run.remediationMode === 'ATTEMPT_UNAPPROVED' && a.sideEffect ? <UnapprovedNote run={run} /> : null}

      <ApprovalSection run={run} />
    </Card>
  );
}

/**
 * Why a policy-denial run looks like a normal one: same incident, same synthetic data, same kind of proposal. The only
 * difference is that the run asked the recommendation agent to try the action before anyone approved it.
 */
function UnapprovedNote({ run }: { run: Run }) {
  const approved = run.outcome === 'REMEDIATION_SIMULATED';
  return (
    <div className="mt-2 rounded-md border border-border bg-surface-raised p-2 text-sm" data-testid="unapproved-note">
      <p className="flex items-center gap-1.5 font-semibold">
        <ShieldCheck aria-hidden size={14} className="text-primary" /> Why this run differs from a normal run
      </p>
      <p className="mt-0.5 text-text-muted">
        The incident, the synthetic data and the proposal are the same. This run was started in{' '}
        <span className="font-mono">ATTEMPT_UNAPPROVED</span> mode, so the recommendation agent tried to execute its own
        proposal before anyone approved it, and policy refused: an agent may propose, but not act alone.{' '}
        {approved
          ? 'A person then approved it, and the same action was allowed and simulated.'
          : 'Approve below to see the same action allowed once a person signs off.'}
      </p>
    </div>
  );
}

function ApprovalSection({ run }: { run: Run }) {
  const qc = useQueryClient();
  const { canMutate, token, reason } = useCanMutate();
  const toast = useNotify((s) => s.toast);
  const announce = useNotify((s) => s.announce);
  const [confirm, setConfirm] = useState<'APPROVE' | 'REJECT' | null>(null);
  const [busy, setBusy] = useState(false);
  const rec = run.recommendation;

  if (run.approval) {
    return (
      <div className="mt-3 rounded-md border border-border bg-surface-raised p-2 text-sm">
        <KeyValue
          items={[
            ['Decision', <Pill tone={run.approval.decision === 'APPROVE' ? 'ok' : 'neutral'}>{run.approval.decision}</Pill>],
            ['Approver', run.approval.approver],
            ['Decided', fmtTime(run.approval.decidedAt)],
            ['Digest', <MonoId value={run.approval.actionDigest} label="Approved digest" />],
          ]}
        />
      </div>
    );
  }
  if (run.state !== 'AWAITING_APPROVAL' || !rec) return null;

  const submit = async (decision: 'APPROVE' | 'REJECT') => {
    if (!canMutate || busy) return;
    setBusy(true);
    try {
      const updated = await api.approve(run.runId, { decision, actionDigest: rec.proposedAction.actionDigest }, token);
      if (updated && typeof updated === 'object' && 'runId' in updated) qc.setQueryData(queryKeys.run(run.runId), updated);
      void qc.invalidateQueries({ queryKey: queryKeys.run(run.runId) });
      void qc.invalidateQueries({ queryKey: queryKeys.timeline(run.runId) });
      void qc.invalidateQueries({ queryKey: queryKeys.runs });
      announce(decision === 'APPROVE' ? 'Approval recorded (simulation only)' : 'Proposed action rejected');
      setConfirm(null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) useSession.getState().markRejected();
      toast({ tone: 'error', title: 'Approval failed', description: describeError(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div id={DECISION_ANCHOR} className="mt-3 scroll-mt-4 border-t border-border pt-3">
      <p className="mb-2 flex items-center gap-1.5 text-sm text-text-muted">
        <ShieldCheck aria-hidden size={14} /> Simulation only — approving records a simulated action; no cluster change.
      </p>
      <div className="flex flex-wrap gap-2">
        <GatedButton variant="primary" icon={Check} disabledReason={reason} onClick={() => setConfirm('APPROVE')}>
          Approve (simulate)
        </GatedButton>
        <GatedButton variant="secondary" icon={X} disabledReason={reason} onClick={() => setConfirm('REJECT')}>
          Reject
        </GatedButton>
      </div>
      <ConfirmDialog
        open={confirm !== null}
        onOpenChange={(o) => !o && setConfirm(null)}
        title={confirm === 'APPROVE' ? 'Approve proposed action?' : 'Reject proposed action?'}
        confirmLabel={confirm === 'APPROVE' ? 'Approve (simulate)' : 'Reject'}
        busy={busy}
        onConfirm={() => confirm && void submit(confirm)}
        body={
          <>
            <p>
              <span className="font-mono">{rec.proposedAction.actionType}</span> on{' '}
              <span className="font-mono">{rec.proposedAction.target}</span>
            </p>
            <p className="font-semibold text-text">Simulation only — no cluster change.</p>
          </>
        }
      />
    </div>
  );
}
