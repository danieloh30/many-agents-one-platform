import { create } from 'zustand';
import { readStorage, writeStorage } from './storage';

export type ThemeChoice = 'light' | 'dark';
export type StageView = 'topology' | 'timeline';
export type InspectorTab = 'summary' | 'evidence' | 'policy' | 'audit' | 'usage';
export type TopologyNodeId =
  | 'console'
  | 'triage'
  | 'evidence-queue'
  | 'evidence-agents'
  | 'recommendation-queue'
  | 'recommendation'
  | 'tool-service'
  | 'opa'
  | 'model';

export const THEME_KEY = 'maop.theme';
const LAST_RUNS_KEY = 'maop.lastRunByScenario';

function initialTheme(): ThemeChoice | null {
  const v = readStorage('local', THEME_KEY);
  return v === 'light' || v === 'dark' ? v : null;
}

function initialLastRuns(): Record<string, string> {
  try {
    const raw = readStorage('session', LAST_RUNS_KEY);
    const v: unknown = raw ? JSON.parse(raw) : {};
    return v && typeof v === 'object' ? (v as Record<string, string>) : {};
  } catch {
    return {};
  }
}

interface UiState {
  /** Mirrors the :runId route parameter for non-React consumers (stream bridge). */
  selectedRunId: string | undefined;
  /** Explicit choice; null follows prefers-color-scheme. */
  themeChoice: ThemeChoice | null;
  presenter: boolean;
  view: StageView;
  inspectorTab: InspectorTab;
  focusNode: TopologyNodeId | null;
  /** Bumped to ask the topology to pan/zoom to focusNode. */
  focusRequest: number;
  helpOpen: boolean;
  tokenDialogOpen: boolean;
  /** Runs started from this browser per scenario, used by the presenter checklist. */
  lastRunByScenario: Record<string, string>;
  /** When this browser started the current burst (ms), for the presenter checklist. */
  burstStartedAt: number | null;
  setTheme(t: ThemeChoice): void;
  togglePresenter(): void;
  setView(v: StageView): void;
  toggleView(): void;
  setInspectorTab(t: InspectorTab): void;
  selectNode(n: TopologyNodeId | null): void;
  focusOn(n: TopologyNodeId): void;
  setHelpOpen(o: boolean): void;
  setTokenDialogOpen(o: boolean): void;
  rememberRun(scenario: string, runId: string): void;
  setBurstStartedAt(t: number | null): void;
  resetDemoState(): void;
}

export const useUi = create<UiState>((set) => ({
  selectedRunId: undefined,
  themeChoice: initialTheme(),
  presenter: false,
  view: 'topology',
  inspectorTab: 'summary',
  focusNode: null,
  focusRequest: 0,
  helpOpen: false,
  tokenDialogOpen: false,
  lastRunByScenario: initialLastRuns(),
  burstStartedAt: null,
  setTheme: (t) => {
    writeStorage('local', THEME_KEY, t);
    set({ themeChoice: t });
  },
  togglePresenter: () => set((s) => ({ presenter: !s.presenter })),
  setView: (view) => set({ view }),
  toggleView: () => set((s) => ({ view: s.view === 'topology' ? 'timeline' : 'topology' })),
  setInspectorTab: (inspectorTab) => set({ inspectorTab }),
  selectNode: (focusNode) => set({ focusNode }),
  focusOn: (n) => set((s) => ({ focusNode: n, view: 'topology', focusRequest: s.focusRequest + 1 })),
  setHelpOpen: (helpOpen) => set({ helpOpen }),
  setTokenDialogOpen: (tokenDialogOpen) => set({ tokenDialogOpen }),
  rememberRun: (scenario, runId) =>
    set((s) => {
      const lastRunByScenario = { ...s.lastRunByScenario, [scenario]: runId };
      writeStorage('session', LAST_RUNS_KEY, JSON.stringify(lastRunByScenario));
      return { lastRunByScenario };
    }),
  setBurstStartedAt: (burstStartedAt) => set({ burstStartedAt }),
  resetDemoState: () => {
    writeStorage('session', LAST_RUNS_KEY, null);
    set({ lastRunByScenario: {}, burstStartedAt: null, focusNode: null });
  },
}));
