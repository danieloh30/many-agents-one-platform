import { Database, Link2 } from "lucide-react";
import { useNavigate } from "react-router";
import type { EvidenceItem } from "../../api/types";
import { useUi } from "../../app/uiStore";
import { fmtPct, humanize } from "../../design/format";
import { ModelBadge } from "./ModelBadge";

export function EvidenceCards({
  runId,
  evidence,
  compact = false,
}: {
  runId: string;
  evidence: EvidenceItem[];
  compact?: boolean;
}) {
  const navigate = useNavigate();
  const openInvocation = (invocationId: string) => {
    useUi.getState().setInspectorTab("audit");
    navigate(`/runs/${runId}/audit/${invocationId}`);
  };
  if (evidence.length === 0)
    return <p className="text-sm text-text-muted">No evidence recorded yet.</p>;
  return (
    <div className="@container">
      <ul className={compact ? "space-y-2" : "grid gap-2 @3xl:grid-cols-2"}>
        {evidence.map((ev) => (
          <li
            key={ev.evidenceId}
            id={`evidence-${ev.evidenceId}`}
            className="min-w-0 scroll-mt-4 rounded-xl border border-border/60 bg-surface p-4"
          >
            <article aria-label={`Evidence: ${humanize(ev.topic)}`}>
              <header className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
                <h4 className="font-semibold">{humanize(ev.topic)}</h4>
                <span className="ml-auto text-sm text-text-muted">
                  Confidence{" "}
                  <span className="tabular font-semibold text-text">
                    {fmtPct(ev.confidence)}
                  </span>
                </span>
              </header>
              <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                <span className="inline-flex items-center gap-1 text-text-muted">
                  <Database aria-hidden size={13} /> {ev.dataOrigin}
                </span>
                <ModelBadge model={ev.model} compact />
              </div>
              <p className="mt-3 text-sm leading-relaxed">{ev.finding}</p>
              {ev.facts.length > 0 ? (
                <ul className="mt-2 space-y-1 border-t border-border pt-2 text-sm">
                  {ev.facts.map((f, i) => (
                    <li key={`${f.invocationId}-${i}`}>
                      <span className="block">{f.statement}</span>
                      <button
                        type="button"
                        onClick={() => openInvocation(f.invocationId)}
                        className="inline-flex items-center gap-1 rounded font-mono text-xs text-primary hover:underline"
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
    </div>
  );
}
