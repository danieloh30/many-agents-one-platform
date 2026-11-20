import { ChevronRight, Database, Link2 } from "lucide-react";
import { useNavigate } from "react-router";
import type { EvidenceItem } from "../../api/types";
import { useUi } from "../../app/uiStore";
import { fmtPct, humanize } from "../../design/format";
import { ModelBadge } from "./ModelBadge";

/** Opens a collapsed evidence card (used by citation links before they scroll to it). */
export function openEvidence(evidenceId: string) {
  const el = document.getElementById(`evidence-${evidenceId}`)?.querySelector("details");
  if (el) el.open = true;
}

export function EvidenceCards({
  runId,
  evidence,
  compact = false,
  collapsible = false,
}: {
  runId: string;
  evidence: EvidenceItem[];
  compact?: boolean;
  /** One line per card (topic, confidence, finding) until opened: keeps a long timeline short. */
  collapsible?: boolean;
}) {
  const navigate = useNavigate();
  const openInvocation = (invocationId: string) => {
    useUi.getState().setInspectorTab("audit");
    navigate(`/runs/${runId}/audit/${invocationId}`);
  };
  if (evidence.length === 0)
    return <p className="text-sm text-text-muted">No evidence recorded yet.</p>;
  const origin = (ev: EvidenceItem) => (
    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
      <span className="inline-flex items-center gap-1 text-text-muted">
        <Database aria-hidden size={13} /> {ev.dataOrigin}
      </span>
      <ModelBadge model={ev.model} compact />
    </div>
  );
  const facts = (ev: EvidenceItem) =>
    ev.facts.length > 0 ? (
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
    ) : null;
  const confidence = (ev: EvidenceItem) => (
    <span className="ml-auto shrink-0 text-sm text-text-muted">
      Confidence{" "}
      <span className="tabular font-semibold text-text">
        {fmtPct(ev.confidence)}
      </span>
    </span>
  );
  if (collapsible) {
    return (
      <ul className="space-y-2">
        {evidence.map((ev) => (
          <li
            key={ev.evidenceId}
            id={`evidence-${ev.evidenceId}`}
            className="min-w-0 scroll-mt-4 rounded-xl border border-border/60 bg-surface"
          >
            <details className="group" data-testid="evidence-card">
              <summary className="flex cursor-pointer list-none flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 [&::-webkit-details-marker]:hidden">
                <ChevronRight
                  aria-hidden
                  size={15}
                  className="ui-transition shrink-0 text-text-muted group-open:rotate-90"
                />
                <h4 className="font-semibold">{humanize(ev.topic)}</h4>
                <span className="text-sm text-text-muted">
                  {ev.facts.length} {ev.facts.length === 1 ? "fact" : "facts"}
                </span>
                {confidence(ev)}
                <p className="basis-full truncate pl-6 text-sm text-text-muted group-open:hidden">
                  {ev.finding}
                </p>
              </summary>
              <article
                aria-label={`Evidence: ${humanize(ev.topic)}`}
                className="px-4 pb-4 pl-10"
              >
                {origin(ev)}
                <p className="mt-3 text-sm leading-relaxed">{ev.finding}</p>
                {facts(ev)}
              </article>
            </details>
          </li>
        ))}
      </ul>
    );
  }
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
                {confidence(ev)}
              </header>
              {origin(ev)}
              <p className="mt-3 text-sm leading-relaxed">{ev.finding}</p>
              {facts(ev)}
            </article>
          </li>
        ))}
      </ul>
    </div>
  );
}
