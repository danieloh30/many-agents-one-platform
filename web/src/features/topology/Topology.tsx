import { Component, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  getBezierPath,
  Handle,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import { Ban, Brain, Inbox, Lock, Monitor, Server, ShieldCheck, type LucideIcon } from 'lucide-react';
import { useLiveStore } from '../../api/liveStore';
import type { LiveEvent } from '../../api/events';
import { useConfig, useInvocations, useOpsSnapshot } from '../../api/queries';
import type { ComponentStatus, OpsSnapshot, PodSnapshot } from '../../api/types';
import { useUi, type TopologyNodeId } from '../../app/uiStore';
import { fmtNum, workloadOf } from '../../design/format';
import { cx, StatusPill } from '../../design/ui';
import { NotOnLocalStack } from '../ops/OpsStrip';
import { modelComponent, platform, podLabel, queue, workload } from '../ops/selectors';
import { ActivityList } from './ActivityList';
import { EDGES, edgesForEvent, eventMatchesRun, NODE_LABELS, NODE_ORDER, NODE_POSITIONS, nodesForEvent, type EdgeKind } from './graph';

// ---- node data -----------------------------------------------------------------------------

interface Line {
  label: string;
  value: string | null;
}

type ServiceNodeData = {
  title: string;
  icon: 'service' | 'queue' | 'console' | 'policy' | 'model';
  status: ComponentStatus | null;
  showStatus: boolean;
  /** Kubernetes-only facts on the cluster-free local stack: shown as a neutral "Not on local stack". */
  localOnly?: boolean;
  lines: Line[];
  pods: PodSnapshot[] | null | undefined;
  showPods: boolean;
  pulse: number;
  selected: boolean;
};

type ServiceNode = Node<ServiceNodeData, 'service'>;

type FlowEdgeData = {
  kind: EdgeKind;
  label: string;
  pulse: number;
  denied: boolean;
  caller: string | null;
};
type FlowEdge = Edge<FlowEdgeData, 'flow'>;

const ICONS: Record<ServiceNodeData['icon'], LucideIcon> = {
  service: Server,
  queue: Inbox,
  console: Monitor,
  policy: ShieldCheck,
  model: Brain,
};

function podTone(label: string) {
  if (label === 'Ready') return 'border-teal/50 text-teal tint-teal';
  if (label === 'Running') return 'border-primary/50 text-primary tint-primary';
  if (label === 'Pending') return 'border-warn/50 text-warn tint-warn';
  return 'border-border text-text-muted';
}

function ServiceNodeView({ data }: NodeProps<ServiceNode>) {
  const Icon = ICONS[data.icon];
  return (
    <div
      className={cx(
        'relative w-[210px] rounded-[10px] border bg-surface px-3 py-2 text-left text-text shadow-sm',
        data.selected ? 'border-primary ring-2 ring-primary/40' : 'border-border',
      )}
    >
      {data.pulse > 0 ? (
        <span key={data.pulse} aria-hidden className="node-pulse pointer-events-none absolute inset-0 rounded-[10px] ring-4 ring-accent" />
      ) : null}
      <Handle type="target" position={Position.Left} className="!h-1.5 !w-1.5 !border-0 !bg-border" />
      <Handle type="source" position={Position.Right} className="!h-1.5 !w-1.5 !border-0 !bg-border" />
      <Handle id="b" type="source" position={Position.Bottom} className="!h-1.5 !w-1.5 !border-0 !bg-border" />
      <Handle id="t" type="target" position={Position.Top} className="!h-1.5 !w-1.5 !border-0 !bg-border" />
      <div className="flex items-center gap-1.5">
        <Icon aria-hidden size={16} className={data.icon === 'model' ? 'text-purple' : 'text-primary'} />
        <span className="text-sm font-semibold leading-tight">{data.title}</span>
      </div>
      {data.showStatus ? (
        <div className="mt-1">
          {data.localOnly ? <NotOnLocalStack /> : <StatusPill status={data.status} />}
        </div>
      ) : null}
      {data.lines.length > 0 ? (
        <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-2 text-sm">
          {data.lines.map((l) => (
            <div key={l.label} className="contents">
              <dt className="text-text-muted">{l.label}</dt>
              <dd className={cx('tabular text-right', l.value === null && 'italic text-text-muted')}>{l.value ?? 'Unavailable'}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {data.showPods ? (
        <div className="mt-1.5" aria-label="Pods">
          {data.pods === null || data.pods === undefined ? (
            <span className="text-sm italic text-text-muted">Pods: Unavailable</span>
          ) : data.pods.length === 0 ? (
            <span className="text-sm text-text-muted">No pods</span>
          ) : (
            <ul className="flex flex-wrap gap-1">
              {data.pods.map((p) => {
                const label = podLabel(p);
                return (
                  <li
                    key={p.name}
                    title={`${p.name} — ${label}${p.activeTasks !== null ? `, ${p.activeTasks} active tasks` : ''}`}
                    className={cx('rounded-full border px-1.5 text-sm leading-5', podTone(label))}
                  >
                    {label}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  );
}

// ---- edges ---------------------------------------------------------------------------------

const EDGE_COLOR: Record<EdgeKind, string> = {
  http: 'var(--primary)',
  async: 'var(--text-muted)',
  result: 'var(--text-muted)',
  mtls: 'var(--primary)',
  policy: 'var(--teal)',
  model: 'var(--purple)',
};

function FlowEdgeView(props: EdgeProps<FlowEdge>) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data } = props;
  const denied = data?.denied ?? false;
  // A denied call stops before the tool node.
  const tx = denied ? targetX - 34 : targetX;
  const [path, labelX, labelY] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX: tx, targetY, targetPosition });
  const kind = data?.kind ?? 'async';
  const dashed = kind === 'async' || kind === 'result';
  const color = denied ? 'var(--danger)' : EDGE_COLOR[kind];
  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        style={{ stroke: color, strokeWidth: denied ? 2.5 : 1.5, strokeDasharray: dashed ? '6 5' : denied ? '2 4' : undefined, opacity: 0.85 }}
      />
      {data && data.pulse > 0 ? (
        <path
          key={data.pulse}
          d={path}
          className="edge-pulse"
          fill="none"
          stroke={denied ? 'var(--danger)' : 'var(--accent)'}
          strokeLinecap="round"
          strokeDasharray="16 64"
          aria-hidden
        />
      ) : null}
      {kind === 'mtls' || denied ? (
        <EdgeLabelRenderer>
          <div
            className="nodrag nopan absolute flex items-center gap-1 rounded-full border bg-surface px-1.5 text-sm"
            style={{
              transform: `translate(-50%, -50%) translate(${denied ? tx : labelX}px, ${denied ? targetY : labelY}px)`,
              borderColor: color,
              color: denied ? 'var(--danger)' : 'var(--text)',
              pointerEvents: 'all',
            }}
            title={data?.caller ? `Verified caller: ${data.caller}` : 'mTLS with X.509-SVID'}
          >
            {denied ? <Ban aria-hidden size={12} /> : <Lock aria-hidden size={12} />}
            <span>{denied ? 'Denied' : 'mTLS'}</span>
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  );
}

const nodeTypes = { service: ServiceNodeView };
const edgeTypes = { flow: FlowEdgeView };

// ---- data assembly -------------------------------------------------------------------------

function countByEdge(events: LiveEvent[], runId: string | undefined): Record<string, number> {
  const out: Record<string, number> = {};
  for (const e of events) {
    if (!eventMatchesRun(e, runId)) continue;
    for (const id of edgesForEvent(e)) out[id] = (out[id] ?? 0) + 1;
  }
  return out;
}

function countByNode(events: LiveEvent[], runId: string | undefined): Record<string, number> {
  const out: Record<string, number> = {};
  for (const e of events) {
    if (!eventMatchesRun(e, runId)) continue;
    for (const id of nodesForEvent(e)) out[id] = (out[id] ?? 0) + 1;
  }
  return out;
}

function nodeData(id: TopologyNodeId, snap: OpsSnapshot | undefined, modelName: string | null, modelMode: string | null, kube = true) {
  const svc = (name: string): Pick<ServiceNodeData, 'status' | 'showStatus' | 'lines' | 'localOnly'> => {
    const w = workload(snap, name);
    if (!kube) return { status: w?.status ?? null, showStatus: true, localOnly: true, lines: [] };
    return {
      status: w?.status ?? null,
      showStatus: true,
      lines: [{ label: 'Ready / desired', value: w ? `${fmtNum(w.readyReplicas)} / ${fmtNum(w.desiredReplicas)}` : null }],
    };
  };
  const q = (consumer: string): Pick<ServiceNodeData, 'status' | 'showStatus' | 'lines'> => {
    const x = queue(snap, consumer);
    return {
      status: x?.status ?? null,
      showStatus: true,
      lines: [
        { label: 'Pending', value: x && x.pending !== null ? fmtNum(x.pending) : null },
        { label: 'In flight', value: x && x.inFlight !== null ? fmtNum(x.inFlight) : null },
      ],
    };
  };
  switch (id) {
    case 'console':
      return { icon: 'console' as const, status: null, showStatus: false, lines: [] };
    case 'triage':
      return { icon: 'service' as const, ...svc('triage-agent') };
    case 'evidence-queue':
      return { icon: 'queue' as const, ...q('evidence-agents') };
    case 'evidence-agents':
      return { icon: 'service' as const, ...svc('evidence-agent') };
    case 'recommendation-queue':
      return { icon: 'queue' as const, ...q('recommendation-workers') };
    case 'recommendation':
      return { icon: 'service' as const, ...svc('recommendation-agent') };
    case 'tool-service':
      return { icon: 'service' as const, ...svc('tool-service') };
    case 'opa':
      return { icon: 'policy' as const, status: platform(snap, (n) => n === 'opa')?.status ?? null, showStatus: true, lines: [] };
    case 'model':
      return {
        icon: 'model' as const,
        status: modelComponent(snap)?.status ?? null,
        showStatus: true,
        lines: [{ label: modelMode === 'DETERMINISTIC' ? 'Deterministic' : 'Model', value: modelName }],
      };
  }
}

function FocusController() {
  const focusRequest = useUi((s) => s.focusRequest);
  const focusNode = useUi((s) => s.focusNode);
  const flow = useReactFlow();
  useEffect(() => {
    if (!focusRequest || !focusNode) return;
    const reduce = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
    void flow.fitView({ nodes: [{ id: focusNode }], padding: 0.6, duration: reduce ? 0 : 400, maxZoom: 1.3 });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- run only when a new focus request arrives
  }, [focusRequest]);
  return null;
}

/** Re-fit when the canvas changes size (window resize, operations strip folded or opened). */
function ResizeController() {
  const width = useStore((s) => s.width);
  const height = useStore((s) => s.height);
  const flow = useReactFlow();
  useEffect(() => {
    if (!width || !height) return;
    const id = requestAnimationFrame(() => void flow.fitView({ padding: 0.08 }));
    return () => cancelAnimationFrame(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refit only on size changes
  }, [width, height]);
  return null;
}

function TopologyGraph({ runId }: { runId: string | undefined }) {
  const events = useLiveStore((s) => s.events);
  const snap = useOpsSnapshot().data;
  const config = useConfig().data;
  const auditItems = useInvocations(runId).data?.items;
  const focusNode = useUi((s) => s.focusNode);
  const selectNode = useUi((s) => s.selectNode);

  const edgeCounts = useMemo(() => countByEdge(events, runId), [events, runId]);
  const nodeCounts = useMemo(() => countByNode(events, runId), [events, runId]);
  // Events that arrived before this view mounted do not animate: one pulse per new real event.
  const [edgeBase] = useState(edgeCounts);
  const [nodeBase] = useState(nodeCounts);

  const deniedEdges = useMemo(() => {
    const latest: Record<string, { denied: boolean; caller: string | null }> = {};
    for (const e of events) {
      if (e.type !== 'tool.invocation.completed.v1' || !eventMatchesRun(e, runId)) continue;
      const w = workloadOf(e.data.callerSpiffeId);
      const edge = w === 'recommendation-agent' ? 'rec-tool' : w === 'evidence-agent' ? 'workers-tool' : null;
      if (edge) latest[edge] = { denied: e.data.status === 'DENIED', caller: e.data.callerSpiffeId ?? null };
    }
    for (const inv of runId ? (auditItems ?? []) : []) {
      const w = workloadOf(inv.caller.spiffeId);
      const edge = w === 'recommendation-agent' ? 'rec-tool' : w === 'evidence-agent' ? 'workers-tool' : null;
      if (edge && inv.status === 'DENIED' && !latest[edge]) latest[edge] = { denied: true, caller: inv.caller.spiffeId };
      if (edge && !latest[edge]) latest[edge] = { denied: false, caller: inv.caller.spiffeId };
    }
    return latest;
  }, [events, runId, auditItems]);

  const nodes: ServiceNode[] = NODE_ORDER.map((id) => {
    const d = nodeData(id, snap, config?.model.name ?? null, config?.model.mode ?? null, config?.kubernetes?.enabled !== false);
    return {
      id,
      type: 'service',
      position: NODE_POSITIONS[id],
      ariaLabel: `${NODE_LABELS[id]}${d.showStatus ? ('localOnly' in d && d.localOnly ? ', not on local stack' : `, status ${d.status ?? 'unknown'}`) : ''}`,
      data: {
        title: NODE_LABELS[id],
        ...d,
        pods: id === 'evidence-agents' ? (workload(snap, 'evidence-agent')?.pods ?? null) : null,
        showPods: id === 'evidence-agents',
        pulse: Math.max(0, (nodeCounts[id] ?? 0) - (nodeBase[id] ?? 0)),
        selected: focusNode === id,
      },
      draggable: false,
      connectable: false,
    };
  });

  const edges: FlowEdge[] = EDGES.map((def) => ({
    id: def.id,
    source: def.source,
    target: def.target,
    sourceHandle: def.kind === 'model' ? 'b' : undefined,
    targetHandle: def.kind === 'result' || def.kind === 'model' ? 't' : undefined,
    type: 'flow',
    ariaLabel: `${def.label}: ${NODE_LABELS[def.source]} to ${NODE_LABELS[def.target]}`,
    data: {
      kind: def.kind,
      label: def.label,
      pulse: Math.max(0, (edgeCounts[def.id] ?? 0) - (edgeBase[def.id] ?? 0)),
      denied: deniedEdges[def.id]?.denied ?? false,
      caller: deniedEdges[def.id]?.caller ?? null,
    },
  }));

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      fitView
      fitViewOptions={{ padding: 0.08 }}
      minZoom={0.3}
      maxZoom={1.6}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable
      zoomOnScroll={false}
      panOnScroll={false}
      preventScrolling={false}
      onNodeClick={(_, n) => selectNode(n.id as TopologyNodeId)}
      onEdgeClick={(_, e) => selectNode(e.target as TopologyNodeId)}
      onSelectionChange={({ nodes: sel }) => {
        const first = sel[0];
        if (first) selectNode(first.id as TopologyNodeId);
      }}
      aria-label="Live topology"
    >
      <Controls showInteractive={false} />
      <FocusController />
      <ResizeController />
    </ReactFlow>
  );
}

class TopologyBoundary extends Component<{ children: ReactNode; fallback: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

function Legend() {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-text-muted" aria-label="Edge legend">
      <li className="flex items-center gap-1">
        <svg width="28" height="8" aria-hidden>
          <line x1="0" y1="4" x2="28" y2="4" stroke="var(--text-muted)" strokeDasharray="6 5" strokeWidth="2" />
        </svg>
        Async message
      </li>
      <li className="flex items-center gap-1">
        <svg width="28" height="8" aria-hidden>
          <line x1="0" y1="4" x2="28" y2="4" stroke="var(--primary)" strokeWidth="2" />
        </svg>
        <Lock aria-hidden size={12} /> mTLS call
      </li>
      <li className="flex items-center gap-1">
        <svg width="28" height="8" aria-hidden>
          <line x1="0" y1="4" x2="28" y2="4" stroke="var(--danger)" strokeDasharray="2 4" strokeWidth="2.5" />
        </svg>
        <Ban aria-hidden size={12} className="text-danger" /> Denied call
      </li>
      <li className="flex items-center gap-1">
        <svg width="28" height="8" aria-hidden>
          <line x1="0" y1="4" x2="28" y2="4" stroke="var(--purple)" strokeWidth="2" />
        </svg>
        Model call
      </li>
    </ul>
  );
}

export function Topology({ runId, idle }: { runId: string | undefined; idle?: ReactNode }) {
  const fallback = (
    <div className="p-3">
      <p role="alert" className="mb-2 text-sm text-warn">
        The topology graph could not be rendered; showing the activity list instead.
      </p>
      <ActivityList runId={runId} />
    </div>
  );
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center justify-between gap-2 px-3 pt-2">
        <h2 className="section-label">
          Live topology {runId ? '— selected run' : '— all runs'}
        </h2>
        <Legend />
      </div>
      <div className="relative min-h-[320px] flex-1" data-testid="topology">
        <TopologyBoundary fallback={fallback}>
          <ReactFlowProvider>
            {/* Remount per run so pulses count only events that arrive while it is shown. */}
            <TopologyGraph key={runId ?? 'all'} runId={runId} />
          </ReactFlowProvider>
        </TopologyBoundary>
        {idle ? <div className="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center">{idle}</div> : null}
      </div>
      <details className="border-t border-border px-3 py-2">
        <summary className="cursor-pointer text-sm text-text-muted">Activity list (text alternative)</summary>
        <div className="mt-2">
          <ActivityList runId={runId} />
        </div>
      </details>
    </div>
  );
}
