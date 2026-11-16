import { Ban, Database, FlaskConical, Lock, ShieldCheck, TriangleExclamationPoint } from 'lucide-react';
import { useConfig } from '../../api/queries';
import type { ToolInvocation } from '../../api/types';
import { fmtNum, fmtTime, trustDomainOf } from '../../design/format';
import { DecisionPill, JsonViewer, KeyValue, MonoId, Pill } from '../../design/ui';

export function EffectText({ inv }: { inv: ToolInvocation }) {
  if (inv.status === 'DENIED') {
    return (
      <span className="inline-flex items-center gap-1 font-semibold">
        <Ban aria-hidden size={14} className="text-danger" /> No side effect — denied before execution
      </span>
    );
  }
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

export function CallerIdentity({ inv }: { inv: ToolInvocation }) {
  const config = useConfig();
  const mode = config.data?.identity.mode;
  const trustDomain = trustDomainOf(inv.caller.spiffeId) ?? config.data?.identity.trustDomain ?? null;
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

export function InvocationDetail({ inv }: { inv: ToolInvocation }) {
  const d = inv.decision;
  return (
    <article aria-label={`Tool invocation ${inv.toolName}`} className="space-y-3 text-sm">
      <header className="flex flex-wrap items-center gap-1.5">
        <DecisionPill status={inv.status} />
        <span className="font-mono font-semibold">{inv.toolName}</span>
        <Pill tone={inv.toolClass === 'REMEDIATION' ? 'warn' : 'neutral'}>{inv.toolClass}</Pill>
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
        <KeyValue
          items={[
            ['Key', <MonoId value={inv.idempotencyKey} label="Idempotency key" max={36} />],
            ['Executions', fmtNum(inv.executions)],
            ['Requests', fmtNum(inv.requests)],
          ]}
        />
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
