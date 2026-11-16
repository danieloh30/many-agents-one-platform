import { useQuery } from '@tanstack/react-query';
import { api, queryKeys } from './client';

export function useConfig() {
  return useQuery({ queryKey: queryKeys.config, queryFn: ({ signal }) => api.config(signal), staleTime: 60_000 });
}

export function useScenarios() {
  return useQuery({ queryKey: queryKeys.scenarios, queryFn: ({ signal }) => api.scenarios(signal), staleTime: 300_000 });
}

export function useRuns() {
  return useQuery({ queryKey: queryKeys.runs, queryFn: ({ signal }) => api.runs({ limit: 50 }, signal) });
}

export function useRun(runId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.run(runId ?? ''),
    queryFn: ({ signal }) => api.run(runId!, signal),
    enabled: !!runId,
  });
}

export function useTimeline(runId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.timeline(runId ?? ''),
    queryFn: ({ signal }) => api.timeline(runId!, signal),
    enabled: !!runId,
  });
}

export function useInvocations(runId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.invocations(runId ?? ''),
    queryFn: ({ signal }) => api.invocations(runId!, signal),
    enabled: !!runId,
  });
}

export function useInvocation(invocationId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.invocation(invocationId ?? ''),
    queryFn: ({ signal }) => api.invocation(invocationId!, signal),
    enabled: !!invocationId,
  });
}

export function useOpsSnapshot() {
  return useQuery({ queryKey: queryKeys.snapshot, queryFn: ({ signal }) => api.opsSnapshot(signal) });
}

export function useBurstCurrent() {
  return useQuery({ queryKey: queryKeys.burst, queryFn: ({ signal }) => api.burstCurrent(signal) });
}
