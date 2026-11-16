import { z } from 'zod';

/** Client-side mirror of the server limits (ui-design §3.1): title ≤ 120, description ≤ 2000. */
export const incidentFormSchema = z.object({
  title: z.string().trim().min(1, 'Title is required.').max(120, 'Title must be at most 120 characters.'),
  description: z
    .string()
    .trim()
    .min(1, 'Description is required.')
    .max(2000, 'Description must be at most 2000 characters.'),
  service: z
    .string()
    .trim()
    .min(1, 'Service is required.')
    .max(63, 'Service must be at most 63 characters.')
    .regex(/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/, 'Use a lowercase service name, for example "checkout".'),
  remediationMode: z.enum(['PROPOSE_ONLY', 'ATTEMPT_UNAPPROVED']),
  faultInjection: z.enum(['NONE', 'DROP_ACK_ONCE']),
});

export type IncidentFormValues = z.infer<typeof incidentFormSchema>;
export type IncidentFormErrors = Partial<Record<keyof IncidentFormValues, string>>;

export function validateIncident(values: IncidentFormValues): {
  ok: boolean;
  data?: IncidentFormValues;
  errors: IncidentFormErrors;
} {
  const r = incidentFormSchema.safeParse(values);
  if (r.success) return { ok: true, data: r.data, errors: {} };
  const errors: IncidentFormErrors = {};
  for (const issue of r.error.issues) {
    const k = issue.path[0] as keyof IncidentFormValues | undefined;
    if (k && !errors[k]) errors[k] = issue.message;
  }
  return { ok: false, errors };
}
