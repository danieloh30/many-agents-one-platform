import { Ban, Database, FlaskConical, Lock, RotateCcw, ShieldCheck, TriangleExclamationPoint } from 'lucide-react';
import { useConfig } from '../../api/queries';
import type { ToolInvocation } from '../../api/types';
import { fmtNum, fmtTime, trustDomainOf, workloadOf } from '../../design/format';
import { cx, DecisionPill, JsonViewer, KeyValue, MonoId, Pill } from '../../design/ui';

export function EffectText({ inv }: { inv: ToolInvocation }) {
  if (inv.status === 'DENIED') {
    return (
      <span className="inline-flex items-center gap-1 font-semibold">
        <Ban aria-hidden size={14} className="text-danger" /> No side effect — denied before execution
      </span>
    );
  }
  if (inv.toolClass === 'SANDBOXED') return <SandboxText inv={inv} />;
  if (inv.toolClass !== 'REMEDIATION') return <span className="text-text-muted">Read-only tool — no side effect</span>;
  if (inv.sideEffect) {
    const params = Object.entries(inv.sideEffect.parameters ?? {})
      .map(([k, v]) => `${k}=${JSON.stringify(v)}`)
      .join(', ');
    return (
      <span>
        Simulated action <span className="font-mono">{inv.sideEffect.actionType}</span> on{' '}
        <span className="font-mono">{inv.sideEffect.target}</span>
        {params ? <span className="font-mono"> ({params})</span> : null} — recorded as{' '}
        <span className="font-mono">simulated_action</span> {inv.sideEffect.actionId.slice(0, 8)}; no cluster change
      </span>
    );
  }
  return <span className="text-text-muted">No simulated action recorded</span>;
}

/** Where a sandboxed tool ran, as the tool service reported it. Nothing is assumed when the result does not say. */
export function sandboxOf(result: unknown): { mode: string | null; executed: boolean | null } {
  const s = (result as { sandbox?: { mode?: unknown; executed?: unknown } } | null)?.sandbox;
  return {
    mode: typeof s?.mode === 'string' ? s.mode : null,
    executed: typeof s?.executed === 'boolean' ? s.executed : null,
  };
}

/** The sandbox colour (purple), so the mode stands out in a line of text; the words carry the meaning, not the colour. */
export const SANDBOX_MARK = 'rounded border border-purple/40 px-1.5 py-0.5 font-semibold text-purple tint-purple';

function SandboxText({ inv }: { inv: ToolInvocation }) {
  const { mode, executed } = sandboxOf(inv.result);
  if (mode === 'SIMULATED') {
    return (
      <span data-testid="sandbox-effect">
        <span className={SANDBOX_MARK}>Sandbox: simulated</span>. The script was recorded, not executed, and no sandbox
        runtime was contacted — no side effect
      </span>
    );
  }
  return (
    <span data-testid="sandbox-effect">
      <span className={SANDBOX_MARK}>
        Sandbox mode: <span className="font-mono">{mode ?? 'not reported'}</span>
      </span>
      {executed === null ? null : executed ? ' — script executed inside the sandbox' : ' — script not executed'}
    </span>
  );
}

/**
 * Who made the call. `compact` (used in lists) leads with the workload name and how it was verified, and keeps the
 * full SPIFFE ID and certificate facts one click away, so a list of calls stays scannable.
 */
export function CallerIdentity({ inv, compact = false }: { inv: ToolInvocation; compact?: boolean }) {
  const config = useConfig();
  const mode = config.data?.identity.mode;
  const trustDomain = trustDomainOf(inv.caller.spiffeId) ?? config.data?.identity.trustDomain ?? null;
  const verified =
    mode === 'DEV_STATIC' ? (
      <Pill tone="warn" icon={TriangleExclamationPoint}>
        Development static identity
      </Pill>
    ) : (
      <Pill tone="purple" icon={Lock}>
        Verified by mTLS
      </Pill>
    );
  if (compact) {
    return (
      <div className="space-y-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-text-muted">Caller</span>
          <span className="font-mono font-semibold text-purple">{workloadOf(inv.caller.spiffeId) ?? 'Unknown'}</span>
          {verified}
        </div>
        <details className="text-sm">
          <summary className="cursor-pointer text-text-muted">Identity details</summary>
          <div className="mt-1 space-y-1">
            <span className="block font-mono text-sm break-all text-purple" data-testid="caller-spiffe-id">
              {inv.caller.spiffeId}
            </span>
            <KeyValue
              items={[
                ['Trust domain', trustDomain ?? 'Unavailable'],
                ['SVID serial', inv.caller.serial ? <MonoId value={inv.caller.serial} label="SVID serial" /> : 'Unavailable'],
                ['SVID expiry', inv.caller.notAfter ? fmtTime(inv.caller.notAfter) : 'Unavailable'],
              ]}
            />
          </div>
        </details>
      </div>
    );
  }
  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="font-mono text-sm break-all text-purple" data-testid="caller-spiffe-id">
          {inv.caller.spiffeId}
        </span>
        {mode === 'DEV_STATIC' ? (
          <Pill tone="warn" icon={TriangleExclamationPoint}>
            Development static identity
          </Pill>
        ) : (
          <Pill tone="purple" icon={Lock}>
            Verified by mTLS
          </Pill>
        )}
      </div>
      <KeyValue
        items={[
          ['Trust domain', trustDomain ?? 'Unavailable'],
          ['SVID serial', inv.caller.serial ? <MonoId value={inv.caller.serial} label="SVID serial" /> : 'Unavailable'],
          ['SVID expiry', inv.caller.notAfter ? fmtTime(inv.caller.notAfter) : 'Unavailable'],
        ]}
      />
    </div>
  );
}

function Count({ label, value, className, testId }: { label: string; value: number | null; className: string; testId: string }) {
  return (
    <div className={cx('rounded-md border px-3 py-2', className)} data-testid={testId}>
      <div className="text-xs font-semibold uppercase tracking-wide">{label}</div>
      <div className="text-3xl font-semibold leading-tight tabular">{fmtNum(value)}</div>
    </div>
  );
}

/** Requests vs executions, large: a replayed call (requested again, executed once) is the point of scenario 4. */
export function IdempotencyCounts({ inv }: { inv: ToolInvocation }) {
  const replayed = (inv.requests ?? 0) >= 2 && inv.executions === 1;
  const neutral = 'border-border bg-surface-raised text-text';
  return (
    <div className="mb-2 space-y-2">
      <div className="grid grid-cols-2 gap-2">
        <Count label="Requests" value={inv.requests} testId="count-requests" className={replayed ? 'border-warn tint-warn text-warn' : neutral} />
        <Count label="Executions" value={inv.executions} testId="count-executions" className={replayed ? 'border-teal/40 tint-teal text-teal' : neutral} />
      </div>
      {replayed ? (
        <p className="flex items-start gap-1.5 rounded-md border border-warn tint-warn px-2 py-1.5 font-semibold text-warn" data-testid="replay-callout">
          <RotateCcw aria-hidden size={16} className="mt-0.5 shrink-0" />
          <span>
            Requested {inv.requests} times, executed once. The repeat (a redelivered task) matched this idempotency key and got
            the recorded result back.
          </span>
        </p>
      ) : null}
    </div>
  );
}

export function InvocationDetail({ inv }: { inv: ToolInvocation }) {
  const d = inv.decision;
  return (
    <article aria-label={`Tool invocation ${inv.toolName}`} className="space-y-3 text-sm">
      <header className="flex flex-wrap items-center gap-1.5">
        <DecisionPill status={inv.status} />
        <span className="font-mono font-semibold">{inv.toolName}</span>
        <Pill tone={inv.toolClass === 'REMEDIATION' ? 'warn' : inv.toolClass === 'SANDBOXED' ? 'purple' : 'neutral'}>{inv.toolClass}</Pill>
        {inv.simulated ? (
          <Pill tone="neutral" icon={FlaskConical}>
            SIMULATED
          </Pill>
        ) : null}
        {inv.dataOrigin ? (
          <Pill tone="neutral" icon={Database}>
            {inv.dataOrigin}
          </Pill>
        ) : null}
      </header>

      <section aria-label="Verified caller">
        <h4 className="mb-1 font-semibold text-text-muted">Verified caller</h4>
        <CallerIdentity inv={inv} />
      </section>

      <section aria-label="Policy decision">
        <h4 className="mb-1 flex items-center gap-1 font-semibold text-text-muted">
          <ShieldCheck aria-hidden size={14} /> Policy
        </h4>
        <KeyValue
          items={[
            ['Decision', d.allow ? 'allow' : 'deny'],
            ['Decision ID', <MonoId value={d.decisionId} label="Decision ID" />],
            ['Policy revision', <MonoId value={d.policyRevision} label="Policy revision" />],
            [
              'Reasons',
              d.reasons.length ? (
                <span className="flex flex-wrap gap-1">
                  {d.reasons.map((r) => (
                    <span key={r} className="rounded border border-border bg-surface-raised px-1.5 font-mono" data-testid="policy-reason">
                      {r}
                    </span>
                  ))}
                </span>
              ) : (
                'None'
              ),
            ],
          ]}
        />
      </section>

      <section aria-label="Idempotency">
        <h4 className="mb-1 font-semibold text-text-muted">Idempotency</h4>
        <IdempotencyCounts inv={inv} />
        <KeyValue items={[['Key', <MonoId value={inv.idempotencyKey} label="Idempotency key" max={36} />]]} />
      </section>

      <section aria-label="Effect">
        <h4 className="mb-1 font-semibold text-text-muted">Effect</h4>
        <EffectText inv={inv} />
      </section>

      <JsonViewer label="Arguments (as audited)" value={inv.arguments} />
      {inv.status !== 'DENIED' ? <JsonViewer label="Result (synthetic)" value={inv.result} /> : null}
      <p className="text-text-muted">
        Recorded {fmtTime(inv.createdAt)}
        {inv.lastRequestedAt ? ` · last requested ${fmtTime(inv.lastRequestedAt)}` : ''}
      </p>
    </article>
  );
}
