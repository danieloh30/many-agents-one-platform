import { useId, useMemo, useRef, useState, type FormEvent } from 'react';
import { Send } from 'lucide-react';
import { useConfig } from '../../api/queries';
import { GatedButton } from '../../design/ui';
import { usePresets } from './ScenarioPresets';
import { isRunRequest } from './presets';
import { validateIncident, type IncidentFormErrors, type IncidentFormValues } from './schema';
import { newIdempotencyKey, useScenarioActions } from './useScenarioActions';

const initial: IncidentFormValues = {
  title: '',
  description: '',
  service: 'checkout',
  remediationMode: 'PROPOSE_ONLY',
  faultInjection: 'NONE',
};

const field =
  'mt-1 w-full rounded-md border border-border bg-surface px-2 py-1.5 text-sm text-text placeholder:text-text-muted';

export function NewIncidentForm() {
  const id = useId();
  const config = useConfig();
  const { presets } = usePresets();
  const actions = useScenarioActions();
  const [values, setValues] = useState<IncidentFormValues>(initial);
  const [errors, setErrors] = useState<IncidentFormErrors>({});
  // One Idempotency-Key per form instance. It is kept across network retries and replaced only after
  // a successful submission or when the request body changes.
  const keyRef = useRef<string | null>(null);
  const guardId = `form:${id}`;
  const pending = actions.isPending(guardId);

  const services = useMemo(() => {
    const s = new Set<string>();
    for (const p of presets) if (isRunRequest(p.request)) s.add(p.request.service);
    return [...s];
  }, [presets]);

  const update = <K extends keyof IncidentFormValues>(k: K, v: IncidentFormValues[K]) => {
    keyRef.current = null;
    setValues((s) => ({ ...s, [k]: v }));
    if (errors[k]) setErrors((e) => ({ ...e, [k]: undefined }));
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const r = validateIncident(values);
    setErrors(r.errors);
    if (!r.ok || !r.data) return;
    keyRef.current ??= newIdempotencyKey();
    const run = await actions.submitRun(
      guardId,
      {
        title: r.data.title,
        description: r.data.description,
        service: r.data.service,
        scenario: 'CUSTOM',
        remediationMode: r.data.remediationMode,
        faultInjection: config.data?.features.faultInjection ? r.data.faultInjection : 'NONE',
        labels: { source: 'console' },
      },
      keyRef.current,
    );
    if (run) {
      keyRef.current = null;
      setValues(initial);
    }
  };

  const err = (k: keyof IncidentFormValues) =>
    errors[k] ? (
      <p id={`${id}-${k}-err`} className="mt-1 text-sm text-danger">
        {errors[k]}
      </p>
    ) : null;

  return (
    <section aria-labelledby={`${id}-h`}>
      <details className="rounded-lg border border-border bg-surface">
        <summary id={`${id}-h`} className="cursor-pointer px-3 py-2 text-sm font-semibold uppercase tracking-wide text-text-muted">
          New incident
        </summary>
        <form noValidate onSubmit={onSubmit} className="space-y-2 px-3 pb-3">
          <label className="block text-sm">
            Title
            <input
              className={field}
              value={values.title}
              maxLength={200}
              onChange={(e) => update('title', e.target.value)}
              aria-invalid={!!errors.title}
              aria-describedby={errors.title ? `${id}-title-err` : undefined}
            />
          </label>
          {err('title')}
          <label className="block text-sm">
            Description
            <textarea
              className={`${field} min-h-20`}
              value={values.description}
              onChange={(e) => update('description', e.target.value)}
              aria-invalid={!!errors.description}
              aria-describedby={errors.description ? `${id}-description-err` : undefined}
            />
          </label>
          {err('description')}
          <label className="block text-sm">
            Affected service (synthetic catalogue)
            <input
              className={`${field} font-mono`}
              list={`${id}-services`}
              value={values.service}
              onChange={(e) => update('service', e.target.value)}
              aria-invalid={!!errors.service}
              aria-describedby={errors.service ? `${id}-service-err` : undefined}
            />
            <datalist id={`${id}-services`}>
              {services.map((s) => (
                <option key={s} value={s} />
              ))}
            </datalist>
          </label>
          {err('service')}
          <label className="block text-sm">
            Remediation mode
            <select
              className={field}
              value={values.remediationMode}
              onChange={(e) => update('remediationMode', e.target.value as IncidentFormValues['remediationMode'])}
            >
              <option value="PROPOSE_ONLY">Propose only (default)</option>
              <option value="ATTEMPT_UNAPPROVED">Attempt unapproved (policy should deny)</option>
            </select>
          </label>
          {config.data?.features.faultInjection ? (
            <label className="block text-sm">
              Fault injection
              <select
                className={field}
                value={values.faultInjection}
                onChange={(e) => update('faultInjection', e.target.value as IncidentFormValues['faultInjection'])}
              >
                <option value="NONE">None</option>
                <option value="DROP_ACK_ONCE">Drop acknowledgement once</option>
              </select>
            </label>
          ) : null}
          <div className="flex justify-end pt-1">
            <GatedButton
              type="submit"
              variant="primary"
              size="sm"
              icon={Send}
              disabledReason={actions.disabledReason}
              disabled={pending}
            >
              {pending ? 'Submitting…' : 'Submit incident'}
            </GatedButton>
          </div>
        </form>
      </details>
    </section>
  );
}
