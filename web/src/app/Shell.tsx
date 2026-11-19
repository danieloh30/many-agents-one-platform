import { useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router';
import { Dialog } from 'radix-ui';
import {
  GitBranch,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRight,
  PanelRightClose,
  PanelRightOpen,
  RefreshCw,
  TriangleExclamationPoint,
  Workflow,
  X,
} from 'lucide-react';
import { ApiError, describeError, isNetworkError } from '../api/client';
import { useConfig, useRuns } from '../api/queries';
import { useLiveStore } from '../api/liveStore';
import { Button, cx } from '../design/ui';
import { OpsStrip } from '../features/ops/OpsStrip';
import { GUIDE_STEPS } from '../features/presenter/checklist';
import { PresenterBar } from '../features/presenter/PresenterBar';
import { DecisionBar } from '../features/runs/DecisionBar';
import { Inspector } from '../features/runs/Inspector';
import { RunTimelineView } from '../features/runs/RunTimeline';
import { NewIncidentForm } from '../features/scenarios/NewIncidentForm';
import { RecentRuns } from '../features/scenarios/RecentRuns';
import { ScenarioPresets, usePresets } from '../features/scenarios/ScenarioPresets';
import { useScenarioActions } from '../features/scenarios/useScenarioActions';
import { Topology } from '../features/topology/Topology';
import { HelpDialog, TokenDialog } from './Dialogs';
import { LiveRegion, Toaster } from './Notifications';
import { useSession } from './session';
import { useShortcuts } from './shortcuts';
import { StreamBridge } from './StreamBridge';
import { TopBar, toggleFullscreen } from './TopBar';
import { useApplyDocumentState } from './theme';
import { useUi } from './uiStore';

function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => globalThis.matchMedia?.(query).matches ?? true);
  useEffect(() => {
    const mq = globalThis.matchMedia?.(query);
    if (!mq) return;
    const on = () => setMatches(mq.matches);
    mq.addEventListener?.('change', on);
    return () => mq.removeEventListener?.('change', on);
  }, [query]);
  return matches;
}

function ApiBanner() {
  const config = useConfig();
  const qc = useQueryClient();
  const err: unknown = config.error;
  const unreachable = !!err && (isNetworkError(err) || (err instanceof ApiError && err.status >= 500));
  if (!unreachable) return null;
  return (
    <div role="alert" className="flex flex-wrap items-center gap-2 border-b border-danger bg-surface px-3 py-2 text-sm">
      <TriangleExclamationPoint aria-hidden size={16} className="text-danger" />
      <span className="font-semibold text-danger">API unreachable.</span>
      <span className="text-text-muted">{describeError(err)} Anything shown below is stale until the API responds.</span>
      <Button size="sm" icon={RefreshCw} className="ml-auto" onClick={() => void qc.refetchQueries()}>
        Retry
      </Button>
    </div>
  );
}

function OfflineNotice() {
  const status = useLiveStore((s) => s.status);
  if (status !== 'offline') return null;
  return (
    <div role="status" className="border-b border-warn/60 tint-warn px-3 py-1.5 text-sm text-warn">
      Live updates are offline — showing last loaded data. Reconnecting automatically.
    </div>
  );
}

function IdlePrompt() {
  const presenter = useUi((s) => s.presenter);
  return (
    <p className="pointer-events-auto rounded-lg border border-border bg-surface px-4 py-2 text-sm shadow">
      {presenter
        ? 'No runs yet — press Run in the guide above (or Shift+1) to see the agents work.'
        : 'No runs yet — run a scenario from the left rail (or Shift+1) to see the agents work.'}
    </p>
  );
}

export function Shell() {
  const { runId, invocationId } = useParams();
  const navigate = useNavigate();
  const isLg = useMediaQuery('(min-width: 1024px)');
  const presenter = useUi((s) => s.presenter);
  const view = useUi((s) => s.view);
  const setView = useUi((s) => s.setView);
  const railHidden = useUi((s) => s.railHidden);
  const inspectorHidden = useUi((s) => s.inspectorHidden);
  const toggleRail = useUi((s) => s.toggleRail);
  const toggleInspector = useUi((s) => s.toggleInspector);
  const runs = useRuns();
  const { presets } = usePresets();
  const actions = useScenarioActions();
  const [drawer, setDrawer] = useState(false);
  useApplyDocumentState();

  useEffect(() => {
    void useSession.getState().revalidate();
  }, []);

  // A link to a tool invocation (e.g. "Open in Tool audit") must show the inspector even if it was hidden.
  useEffect(() => {
    if (invocationId && useUi.getState().inspectorHidden) useUi.getState().toggleInspector();
  }, [invocationId]);

  const handlers = useMemo(() => {
    const items = runs.data?.items ?? [];
    const idx = items.findIndex((r) => r.runId === runId);
    const go = (i: number) => {
      const r = items[i];
      if (r) navigate(`/runs/${r.runId}`);
    };
    return {
      togglePresenter: () => useUi.getState().togglePresenter(),
      toggleFullscreen,
      openHelp: () => useUi.getState().setHelpOpen(true),
      toggleView: () => useUi.getState().toggleView(),
      runPreset: (i: number) => {
        const p = presets[i];
        if (p && actions.canMutate) void actions.runPreset(p);
        const step = GUIDE_STEPS[i];
        if (step) useUi.getState().setGuideStep(step.id);
      },
      prevRun: () => go(idx <= 0 ? 0 : idx - 1),
      nextRun: () => go(idx < 0 ? 0 : Math.min(items.length - 1, idx + 1)),
      toggleRail: () => useUi.getState().toggleRail(),
      toggleInspector: () => useUi.getState().toggleInspector(),
      toggleGuide: () => {
        if (useUi.getState().presenter) useUi.getState().toggleGuide();
      },
    };
  }, [runs.data, runId, navigate, presets, actions]);
  useShortcuts(handlers);

  const noRuns = runs.data?.items.length === 0 && !runId;

  const stage = (
    <div className="flex min-h-[26rem] flex-1 flex-col">
      <div role="tablist" aria-label="Stage view (T)" className="flex items-end gap-1 border-b border-border px-3 pt-2">
        {isLg && !presenter ? (
          <Button
            size="sm"
            variant="ghost"
            icon={railHidden ? PanelLeftOpen : PanelLeftClose}
            className="mb-1 mr-1"
            onClick={toggleRail}
            aria-pressed={!railHidden}
            aria-label={railHidden ? 'Show scenarios and runs (L)' : 'Hide scenarios and runs (L)'}
            title={railHidden ? 'Show scenarios and runs (L)' : 'Hide scenarios and runs (L)'}
          />
        ) : null}
        {(
          [
            ['topology', 'Topology', Workflow],
            ['timeline', 'Timeline', GitBranch],
          ] as const
        ).map(([v, label, Icon]) => (
          <button
            key={v}
            role="tab"
            type="button"
            aria-selected={view === v}
            onClick={() => setView(v)}
            className={cx(
              'ui-transition inline-flex items-center gap-1 rounded-t-md border-b-2 px-2 py-1.5 text-sm',
              view === v ? 'border-primary font-semibold text-text' : 'border-transparent text-text-muted hover:text-text',
            )}
          >
            <Icon aria-hidden size={14} /> {label}
          </button>
        ))}
        {!isLg ? (
          <Button size="sm" variant="ghost" icon={PanelRight} className="ml-auto mb-1" onClick={() => setDrawer(true)}>
            Inspector
          </Button>
        ) : (
          <Button
            size="sm"
            variant="ghost"
            icon={inspectorHidden ? PanelRightOpen : PanelRightClose}
            className="ml-auto mb-1"
            onClick={toggleInspector}
            aria-pressed={!inspectorHidden}
            aria-label={inspectorHidden ? 'Show inspector (I)' : 'Hide inspector (I)'}
            title={inspectorHidden ? 'Show inspector (I)' : 'Hide inspector (I)'}
          />
        )}
      </div>
      <DecisionBar runId={runId} />
      <div role="tabpanel" aria-label={view === 'topology' ? 'Topology' : 'Timeline'} className={cx('min-h-0 flex-1', view === 'timeline' && 'overflow-auto')}>
        {view === 'topology' ? (
          <Topology runId={runId} idle={noRuns ? <IdlePrompt /> : undefined} />
        ) : (
          <RunTimelineView runId={runId} />
        )}
      </div>
    </div>
  );

  return (
    <div className={cx('flex flex-col bg-bg text-text', isLg ? 'h-screen' : 'min-h-screen')}>
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:bg-surface focus:p-2">
        Skip to main content
      </a>
      <StreamBridge selectedRunId={runId} />
      <TopBar />
      <ApiBanner />
      <OfflineNotice />
      <div className={cx('flex min-h-0 flex-1', !isLg && 'flex-col')}>
        {!presenter && !(isLg && railHidden) ? (
          <nav aria-label="Scenarios and runs" className={cx('space-y-4 overflow-auto p-3', isLg && 'w-80 shrink-0 border-r border-border 2xl:w-[22rem]')}>
            <ScenarioPresets />
            <NewIncidentForm />
            <RecentRuns />
          </nav>
        ) : null}
        <main id="main" className={cx('flex min-w-0 flex-1 flex-col', isLg ? 'overflow-y-auto' : 'min-h-[80vh]')}>
          {presenter ? <PresenterBar /> : null}
          {stage}
          <OpsStrip />
        </main>
        {isLg && inspectorHidden ? null : isLg ? (
          <div className={cx('shrink-0 border-l border-border bg-surface', presenter ? 'w-[26rem] 2xl:w-[30rem]' : 'w-96 2xl:w-[26rem]')}>
            <Inspector runId={runId} invocationId={invocationId} />
          </div>
        ) : (
          <Dialog.Root open={drawer} onOpenChange={setDrawer}>
            <Dialog.Portal>
              <Dialog.Overlay className="fixed inset-0 z-40 bg-black/50" />
              <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-[min(100vw,28rem)] flex-col bg-surface text-text shadow-2xl">
                <div className="flex items-center justify-between border-b border-border p-2">
                  <Dialog.Title className="font-semibold">Inspector</Dialog.Title>
                  <Dialog.Description className="sr-only">Details for the selected run</Dialog.Description>
                  <Dialog.Close asChild>
                    <Button size="sm" variant="ghost" icon={X} aria-label="Close inspector" />
                  </Dialog.Close>
                </div>
                <div className="min-h-0 flex-1">
                  <Inspector runId={runId} invocationId={invocationId} />
                </div>
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
        )}
      </div>
      <TokenDialog />
      <HelpDialog />
      <Toaster />
      <LiveRegion />
    </div>
  );
}
