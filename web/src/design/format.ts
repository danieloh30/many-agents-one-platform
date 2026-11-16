/** Formatting helpers. Null/undefined never becomes 0: callers render "Unavailable" instead. */

export const UNAVAILABLE = 'Unavailable';

export function fmtNum(n: number | null | undefined, digits = 0): string {
  if (n === null || n === undefined || Number.isNaN(n)) return UNAVAILABLE;
  return n.toLocaleString('en-US', { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

export function fmtPct(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) return UNAVAILABLE;
  return `${Math.round(n * 100)}%`;
}

export function fmtSeconds(s: number | null | undefined): string {
  if (s === null || s === undefined || Number.isNaN(s)) return UNAVAILABLE;
  if (s < 60) return `${s.toFixed(s < 10 ? 1 : 0)} s`;
  const m = Math.floor(s / 60);
  return `${m} min ${Math.round(s % 60)} s`;
}

export function fmtDuration(ms: number): string {
  if (ms < 1000) return `${Math.max(0, Math.round(ms))} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(s < 10 ? 1 : 0)} s`;
  const m = Math.floor(s / 60);
  return `${m}m ${Math.round(s % 60)}s`;
}

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return UNAVAILABLE;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return UNAVAILABLE;
  return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

export function ageSeconds(iso: string | null | undefined, now: number): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return null;
  return Math.max(0, (now - t) / 1000);
}

export function humanize(enumValue: string | null | undefined): string {
  if (!enumValue) return UNAVAILABLE;
  return enumValue
    .toLowerCase()
    .split('_')
    .map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w))
    .join(' ');
}

export function middleTruncate(s: string, max = 28): string {
  if (s.length <= max) return s;
  const keep = max - 1;
  const head = Math.ceil(keep / 2);
  const tail = Math.floor(keep / 2);
  return `${s.slice(0, head)}…${s.slice(s.length - tail)}`;
}

/** spiffe://<trust-domain>/... → trust domain. */
export function trustDomainOf(spiffeId: string | null | undefined): string | null {
  if (!spiffeId) return null;
  const m = /^spiffe:\/\/([^/]+)/.exec(spiffeId);
  return m?.[1] ?? null;
}

/** spiffe://td/ns/<ns>/sa/<sa> → service account (workload) name. */
export function workloadOf(spiffeId: string | null | undefined): string | null {
  if (!spiffeId) return null;
  const m = /\/sa\/([^/]+)$/.exec(spiffeId);
  return m?.[1] ?? null;
}
