import { create } from 'zustand';
import type { LiveEvent } from './events';
import type { StreamStatus } from './streamClient';
import type { OpsSnapshot } from './types';

const MAX_EVENTS = 600;
/** Five minutes of snapshots at the 2 s cadence. */
const HISTORY_WINDOW_MS = 5 * 60 * 1000;

export interface SnapshotPoint {
  /** Snapshot observedAt (ms). */
  t: number;
  pending: number | null;
  inFlight: number | null;
  desired: number | null;
  ready: number | null;
  min: number | null;
}

interface LiveState {
  status: StreamStatus;
  /** Events received since page load, oldest first. */
  events: LiveEvent[];
  /** Snapshot points received in this browser session ("since page load"). */
  history: SnapshotPoint[];
  setStatus(s: StreamStatus): void;
  addEvent(e: LiveEvent): void;
  addSnapshot(s: OpsSnapshot): void;
  clearEvents(): void;
}

export const EVIDENCE_CONSUMER = 'evidence-agents';
export const EVIDENCE_WORKLOAD = 'evidence-agent';

export function toPoint(s: OpsSnapshot): SnapshotPoint | null {
  const t = Date.parse(s.observedAt);
  if (Number.isNaN(t)) return null;
  const q = s.queues.find((x) => x.consumer === EVIDENCE_CONSUMER);
  const w = s.workloads.find((x) => x.name === EVIDENCE_WORKLOAD);
  return {
    t,
    pending: q?.pending ?? null,
    inFlight: q?.inFlight ?? null,
    desired: w?.desiredReplicas ?? null,
    ready: w?.readyReplicas ?? null,
    min: w?.minReplicas ?? null,
  };
}

export const useLiveStore = create<LiveState>((set) => ({
  status: 'connecting',
  events: [],
  history: [],
  setStatus: (status) => set({ status }),
  addEvent: (e) =>
    set((st) => {
      if (st.events.some((x) => x.id === e.id && x.type === e.type)) return st; // replayed duplicate
      const events = st.events.length >= MAX_EVENTS ? st.events.slice(-MAX_EVENTS + 1) : st.events.slice();
      events.push(e);
      return { events };
    }),
  addSnapshot: (s) =>
    set((st) => {
      const p = toPoint(s);
      if (!p) return st;
      const last = st.history[st.history.length - 1];
      if (last && last.t >= p.t) return st;
      const cutoff = p.t - HISTORY_WINDOW_MS;
      return { history: [...st.history.filter((x) => x.t >= cutoff), p] };
    }),
  clearEvents: () => set({ events: [] }),
}));
