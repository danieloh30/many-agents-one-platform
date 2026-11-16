import { Database, Link2 } from 'lucide-react';
import { useNavigate } from 'react-router';
import type { EvidenceItem } from '../../api/types';
import { useUi } from '../../app/uiStore';
import { fmtPct, humanize } from '../../design/format';
import { Pill } from '../../design/ui';
import { ModelBadge } from './ModelBadge';

export function EvidenceCards({ runId, evidence, compact = false }: { runId: string; evidence: EvidenceItem[]; compact?: boolean }) {
  const navigate = useNavigate();
  const openInvocation = (invocationId: string) => {
    useUi.getState().setInspectorTab('audit');
    navigate(`/runs/${runId}/audit/${invocationId}`);
  };
  if (evidence.length === 0) return <p className="text-sm text-text-muted">No evidence recorded yet.</p>;
  return (
    <ul className={compact ? 'space-y-2' : 'grid gap-2 xl:grid-cols-2'}>
      {evidence.map((ev) => (
        <li
          key={ev.evidenceId}
          id={`evidence-${ev.evidenceId}`}
          className="scroll-mt-4 rounded-lg border border-border bg-surface p-3"
        >
          <article aria-label={`Evidence: ${humanize(ev.topic)}`}>
            <header className="flex flex-wrap items-center gap-1.5">
              <h4 className="font-semibold">{humanize(ev.topic)}</h4>
              <Pill tone="neutral" icon={Database}>
                {ev.dataOrigin}
              </Pill>
              <ModelBadge model={ev.model} />
              <span className="ml-auto text-sm text-text-muted">
                Confidence <span className="tabular font-semibold text-text">{fmtPct(ev.confidence)}</span>
              </span>
            </header>
            <p className="mt-1.5 text-sm">{ev.finding}</p>
            {ev.facts.length > 0 ? (
              <ul className="mt-2 space-y-1 border-t border-border pt-2 text-sm">
                {ev.facts.map((f, i) => (
                  <li key={`${f.invocationId}-${i}`} className="flex items-start gap-1.5">
                    <span className="flex-1">{f.statement}</span>
                    <button
                      type="button"
                      onClick={() => openInvocation(f.invocationId)}
                      className="inline-flex shrink-0 items-center gap-1 rounded px-1 font-mono text-sm text-primary hover:underline"
                      aria-label={`Open tool invocation ${f.toolName} in the audit tab`}
                    >
                      <Link2 aria-hidden size={13} />
                      {f.toolName}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </article>
        </li>
      ))}
    </ul>
  );
}
