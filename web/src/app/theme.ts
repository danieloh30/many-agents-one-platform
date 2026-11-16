import { useEffect, useState } from 'react';
import { useUi, type ThemeChoice } from './uiStore';

const QUERY = '(prefers-color-scheme: dark)';

function systemTheme(): ThemeChoice {
  return globalThis.matchMedia?.(QUERY).matches ? 'dark' : 'light';
}

/** Follows prefers-color-scheme until the user chooses; the choice persists in localStorage. */
export function useEffectiveTheme(): ThemeChoice {
  const choice = useUi((s) => s.themeChoice);
  const [system, setSystem] = useState<ThemeChoice>(systemTheme);
  useEffect(() => {
    const mq = globalThis.matchMedia?.(QUERY);
    if (!mq) return;
    const on = () => setSystem(mq.matches ? 'dark' : 'light');
    mq.addEventListener?.('change', on);
    return () => mq.removeEventListener?.('change', on);
  }, []);
  return choice ?? system;
}

export function useApplyDocumentState() {
  const theme = useEffectiveTheme();
  const presenter = useUi((s) => s.presenter);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  useEffect(() => {
    document.documentElement.dataset.presenter = String(presenter);
  }, [presenter]);
}
