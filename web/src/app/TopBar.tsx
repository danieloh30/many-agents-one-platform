import {
  Brain,
  CircleQuestionMark,
  Database,
  FlaskConical,
  KeyRound,
  Loader,
  Maximize,
  Minimize,
  Moon,
  Presentation,
  Radio,
  RefreshCw,
  Sun,
  WifiOff,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import { useLiveStore } from '../api/liveStore';
import { useConfig } from '../api/queries';
import type { StreamStatus } from '../api/streamClient';
import { Button, Pill, Tip } from '../design/ui';
import { ResetControl } from '../features/presenter/ResetControl';
import { usePresenterTokenRequired, useSession } from './session';
import { useEffectiveTheme } from './theme';
import { useUi } from './uiStore';

export function ModelBadge() {
  const config = useConfig();
  if (config.isPending) return <Pill tone="neutral">Model: loading</Pill>;
  if (!config.data) return <Pill tone="neutral">Model: Unavailable</Pill>;
  const m = config.data.model;
  if (m.mode === 'DETERMINISTIC') {
    return (
      <Tip content="Scripted model outputs for rehearsal and tests. No live model is called.">
        <span tabIndex={0} className="inline-flex rounded-full">
          <Pill tone="warn" icon={FlaskConical} testId="model-badge" className="border-2 uppercase tracking-wide">
            Deterministic
          </Pill>
        </span>
      </Tip>
    );
  }
  return (
    <Pill tone="purple" icon={Brain} testId="model-badge">
      LIVE {m.name ?? ''}
    </Pill>
  );
}

const STREAM: Record<StreamStatus, { text: string; tone: 'live' | 'neutral' | 'warn' | 'danger'; icon: typeof Radio }> = {
  connecting: { text: 'Connecting', tone: 'neutral', icon: Loader },
  live: { text: 'Live', tone: 'live', icon: Radio },
  reconnecting: { text: 'Reconnecting', tone: 'warn', icon: RefreshCw },
  offline: { text: 'Offline — showing last loaded data', tone: 'danger', icon: WifiOff },
};

export function StreamStatusPill() {
  const status = useLiveStore((s) => s.status);
  const m = STREAM[status];
  return (
    <span role="status" aria-label={`Event stream: ${m.text}`}>
      <Pill tone={m.tone} icon={m.icon} testId="stream-status">
        {m.text}
      </Pill>
    </span>
  );
}

function useFullscreen(): [boolean, () => void] {
  const [fs, setFs] = useState(() => typeof document !== 'undefined' && !!document.fullscreenElement);
  useEffect(() => {
    const on = () => setFs(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', on);
    return () => document.removeEventListener('fullscreenchange', on);
  }, []);
  return [fs, toggleFullscreen];
}

export function toggleFullscreen() {
  if (typeof document === 'undefined') return;
  if (document.fullscreenElement) void document.exitFullscreen?.();
  else void document.documentElement.requestFullscreen?.().catch(() => undefined);
}

export function TopBar() {
  const theme = useEffectiveTheme();
  const setTheme = useUi((s) => s.setTheme);
  const presenter = useUi((s) => s.presenter);
  const togglePresenter = useUi((s) => s.togglePresenter);
  const setHelpOpen = useUi((s) => s.setHelpOpen);
  const setTokenDialogOpen = useUi((s) => s.setTokenDialogOpen);
  const session = useSession((s) => s.state);
  const tokenRequired = usePresenterTokenRequired();
  const [fs, toggleFs] = useFullscreen();

  return (
    <header className="flex flex-wrap items-center gap-2 border-b border-border bg-surface px-3 py-2">
      <h1 className="mr-2 text-base font-bold tracking-tight">Agent Operations Console</h1>
      <div
        role="group"
        aria-label="Console status"
        className="inline-flex flex-wrap items-center gap-x-3 gap-y-1.5"
      >
        <ModelBadge />
        <span className="inline-flex items-center gap-1 text-sm text-text-muted" data-testid="synthetic-badge">
          <Database aria-hidden size={14} /> SYNTHETIC DATA
        </span>
        <StreamStatusPill />
      </div>
      <div className="ml-auto flex flex-wrap items-center gap-1">
        {tokenRequired ? (
          <Button
            size="sm"
            variant={session === 'valid' ? 'secondary' : 'ghost'}
            icon={KeyRound}
            onClick={() => setTokenDialogOpen(true)}
          >
            {session === 'valid' ? 'Presenter token active' : 'Presenter sign-in'}
          </Button>
        ) : null}
        {/* Always reachable; presenter mode shows the same control in its guide bar instead. */}
        {presenter ? null : <ResetControl />}
        <div
          role="group"
          aria-label="Color theme"
          data-testid="theme-switch"
          className="mr-1 inline-flex items-center rounded-md border border-border bg-surface-raised p-0.5"
        >
          <Button
            size="sm"
            variant={theme === 'light' ? 'secondary' : 'ghost'}
            icon={Sun}
            onClick={() => setTheme('light')}
            aria-pressed={theme === 'light'}
            data-testid="theme-light"
            className="border-transparent"
          >
            Light
          </Button>
          <Button
            size="sm"
            variant={theme === 'dark' ? 'secondary' : 'ghost'}
            icon={Moon}
            onClick={() => setTheme('dark')}
            aria-pressed={theme === 'dark'}
            data-testid="theme-dark"
            className="border-transparent"
          >
            Dark
          </Button>
        </div>
        <Button
          size="sm"
          variant={presenter ? 'secondary' : 'ghost'}
          icon={Presentation}
          onClick={togglePresenter}
          aria-pressed={presenter}
          aria-label="Presenter mode (P)"
        />
        <Button size="sm" variant="ghost" icon={fs ? Minimize : Maximize} onClick={toggleFs} aria-label="Full screen (F)" />
        <Button size="sm" variant="ghost" icon={CircleQuestionMark} onClick={() => setHelpOpen(true)} aria-label="Keyboard shortcuts (?)" />
      </div>
    </header>
  );
}
