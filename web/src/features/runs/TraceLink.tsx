import { ExternalLink } from 'lucide-react';
import { useConfig, useOpsSnapshot } from '../../api/queries';
import { Tip } from '../../design/ui';

/** "Open trace in Jaeger" — disabled with a reason when traceId or Jaeger is missing/unavailable. */
export function TraceLink({ traceId }: { traceId: string | null | undefined }) {
  const config = useConfig();
  const snapshot = useOpsSnapshot();
  const base = config.data?.links.jaeger ?? null;
  const jaegerStatus = snapshot.data?.platform.find((p) => p.component === 'jaeger')?.status;
  let reason: string | null = null;
  if (!traceId) reason = 'No trace ID recorded for this run.';
  else if (!base) reason = 'Jaeger is not exposed on this deployment.';
  else if (jaegerStatus === 'UNAVAILABLE') reason = 'Jaeger is reported unavailable.';
  if (reason || !base || !traceId) {
    return (
      <Tip content={reason}>
        <span
          tabIndex={0}
          aria-disabled="true"
          className="inline-flex cursor-not-allowed items-center gap-1 rounded-md border border-border px-2 py-1 text-sm text-text-muted opacity-70"
        >
          <ExternalLink aria-hidden size={14} /> Open trace in Jaeger
          <span className="sr-only"> (unavailable: {reason})</span>
        </span>
      </Tip>
    );
  }
  return (
    <a
      href={`${base.replace(/\/$/, '')}/trace/${traceId}`}
      target="_blank"
      rel="noreferrer noopener"
      className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-sm text-primary hover:bg-surface-raised"
    >
      <ExternalLink aria-hidden size={14} /> Open trace in Jaeger
    </a>
  );
}
