/** Browser storage access that never throws (private mode, blocked storage, tests). */
export function readStorage(kind: 'local' | 'session', key: string): string | null {
  try {
    const s = kind === 'local' ? globalThis.localStorage : globalThis.sessionStorage;
    return s?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

export function writeStorage(kind: 'local' | 'session', key: string, value: string | null): void {
  try {
    const s = kind === 'local' ? globalThis.localStorage : globalThis.sessionStorage;
    if (!s) return;
    if (value === null) s.removeItem(key);
    else s.setItem(key, value);
  } catch {
    // storage unavailable: the preference lives for this page only
  }
}
