import type { OpsSnapshot, PlatformComponent, PodSnapshot } from '../../api/types';

export const workload = (s: OpsSnapshot | undefined, name: string) => s?.workloads.find((w) => w.name === name);
export const queue = (s: OpsSnapshot | undefined, consumer: string) => s?.queues.find((q) => q.consumer === consumer);
export const platform = (s: OpsSnapshot | undefined, match: (name: string) => boolean): PlatformComponent | undefined =>
  s?.platform.find((p) => match(p.component.toLowerCase()));
export const modelComponent = (s: OpsSnapshot | undefined) => platform(s, (n) => n.includes('model'));

export type PodLabel = 'Ready' | 'Running' | 'Pending' | string;

/** Pod pill label strictly from reported fields; "Ready" only when the snapshot says ready=true. */
export function podLabel(p: PodSnapshot): PodLabel {
  if (p.ready === true) return 'Ready';
  if (p.phase) return p.phase;
  return 'Unknown';
}

const COMPONENT_LABELS: Record<string, string> = {
  opa: 'OPA',
  spire: 'SPIRE',
  nats: 'NATS',
  jaeger: 'Jaeger',
  prometheus: 'Prometheus',
  grafana: 'Grafana',
  keda: 'KEDA',
  model: 'Model endpoint',
  'model-endpoint': 'Model endpoint',
  postgresql: 'PostgreSQL',
};

export function componentLabel(name: string): string {
  return COMPONENT_LABELS[name.toLowerCase()] ?? name;
}
