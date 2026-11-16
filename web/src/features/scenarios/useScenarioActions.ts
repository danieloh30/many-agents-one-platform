import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { create } from 'zustand';
import { api, ApiError, describeError, queryKeys } from '../../api/client';
import type { BurstRequest, CreateRunRequest, Run, ScenarioPreset } from '../../api/types';
import { useNotify } from '../../app/notify';
import { useCanMutate, useSession } from '../../app/session';
import { useUi } from '../../app/uiStore';
import { burstRequest, isBurstPreset, isRunRequest, presetUnavailableReason } from './presets';

/** Synchronous in-flight guard: a second click before React re-renders cannot send a second request. */
const inFlight = new Set<string>();

const usePending = create<{ pending: Record<string, boolean>; set(id: string, v: boolean): void }>((set) => ({
  pending: {},
  set: (id, v) => set((s) => ({ pending: { ...s.pending, [id]: v } })),
}));

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export function useScenarioActions() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { canMutate, token, reason } = useCanMutate();
  const pending = usePending((s) => s.pending);
  const setPending = usePending((s) => s.set);
  const toast = useNotify((s) => s.toast);
  const announce = useNotify((s) => s.announce);

  const handleError = (e: unknown, what: string) => {
    if (e instanceof ApiError && e.status === 401) {
      useSession.getState().markRejected();
      toast({ tone: 'error', title: 'Presenter token rejected', description: 'Enter the presenter token again.' });
      return;
    }
    toast({ tone: 'error', title: `${what} failed`, description: describeError(e) });
  };

  async function guarded<T>(id: string, fn: () => Promise<T>): Promise<T | null> {
    if (inFlight.has(id)) return null;
    inFlight.add(id);
    setPending(id, true);
    try {
      return await fn();
    } finally {
      inFlight.delete(id);
      setPending(id, false);
    }
  }

  /** Submits a run. The caller owns the Idempotency-Key (one per form instance or preset click). */
  async function submitRun(guardId: string, request: CreateRunRequest, idempotencyKey: string): Promise<Run | null> {
    if (!token) return null;
    return guarded(guardId, async () => {
      try {
        const run = await api.createRun(request, token, idempotencyKey);
        useUi.getState().rememberRun(request.scenario, run.runId);
        qc.setQueryData(queryKeys.run(run.runId), run);
        void qc.invalidateQueries({ queryKey: queryKeys.runs });
        announce(`Run submitted: ${request.title}`);
        navigate(`/runs/${run.runId}`);
        return run;
      } catch (e) {
        handleError(e, 'Submitting the incident');
        return null;
      }
    });
  }

  async function startBurst(request: BurstRequest) {
    if (!token) return null;
    return guarded('BURST', async () => {
      try {
        const status = await api.startBurst(request, token);
        useUi.getState().setBurstStartedAt(Date.now());
        if (status && typeof status === 'object' && 'active' in status) qc.setQueryData(queryKeys.burst, status);
        void qc.invalidateQueries({ queryKey: queryKeys.burst });
        void qc.invalidateQueries({ queryKey: queryKeys.runs });
        announce('Burst started');
        return status;
      } catch (e) {
        if (e instanceof ApiError && e.code === 'BURST_ACTIVE') {
          toast({ tone: 'info', title: 'A burst is already running', description: describeError(e) });
          void qc.invalidateQueries({ queryKey: queryKeys.burst });
          return null;
        }
        handleError(e, 'Starting the burst');
        return null;
      }
    });
  }

  async function stopBurst() {
    if (!token) return null;
    return guarded('BURST_STOP', async () => {
      try {
        await api.stopBurst(token);
        void qc.invalidateQueries({ queryKey: queryKeys.burst });
        void qc.invalidateQueries({ queryKey: queryKeys.runs });
        announce('Burst stop requested');
        return true;
      } catch (e) {
        handleError(e, 'Stopping the burst');
        return null;
      }
    });
  }

  /** Fires a preset: a fresh Idempotency-Key per click. */
  async function runPreset(preset: ScenarioPreset) {
    if (isBurstPreset(preset)) return startBurst(burstRequest(preset));
    if (!isRunRequest(preset.request)) return null;
    return submitRun(`preset:${preset.id}`, preset.request, newIdempotencyKey());
  }

  function presetDisabledReason(preset: ScenarioPreset, config: Parameters<typeof presetUnavailableReason>[1]) {
    return reason ?? presetUnavailableReason(preset, config);
  }

  return {
    canMutate,
    token,
    disabledReason: reason,
    pending,
    isPending: (id: string) => !!pending[id],
    submitRun,
    runPreset,
    startBurst,
    stopBurst,
    presetDisabledReason,
    handleError,
  };
}
