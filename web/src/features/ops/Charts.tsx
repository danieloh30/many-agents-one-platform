import { useId, useState } from 'react';
import type { SnapshotPoint } from '../../api/liveStore';
import { fmtNum, fmtTime } from '../../design/format';

const W = 220;
const H = 44;

function scale(points: SnapshotPoint[], key: (p: SnapshotPoint) => number | null) {
  const vals = points.map(key).filter((v): v is number => v !== null);
  const max = Math.max(1, ...vals);
  const t0 = points[0]?.t ?? 0;
  const t1 = points[points.length - 1]?.t ?? t0 + 1;
  const span = Math.max(1, t1 - t0);
  return {
    x: (t: number) => ((t - t0) / span) * W,
    y: (v: number) => H - 2 - (v / max) * (H - 6),
    max,
  };
}

/** Build path segments, breaking the line where a value was unavailable (null is never drawn as 0). */
function segments(points: SnapshotPoint[], key: (p: SnapshotPoint) => number | null, step: boolean) {
  const s = scale(points, key);
  const paths: string[] = [];
  let cur = '';
  let prevY: number | null = null;
  for (const p of points) {
    const v = key(p);
    if (v === null) {
      if (cur) paths.push(cur);
      cur = '';
      prevY = null;
      continue;
    }
    const x = s.x(p.t).toFixed(1);
    const y = s.y(v).toFixed(1);
    if (!cur) cur = `M${x},${y}`;
    else cur += step && prevY !== null ? ` H${x} V${y}` : ` L${x},${y}`;
    prevY = Number(y);
  }
  if (cur) paths.push(cur);
  return { paths, max: s.max };
}

function DataTable({ points, columns }: { points: SnapshotPoint[]; columns: Array<[string, (p: SnapshotPoint) => number | null]> }) {
  return (
    <table className="mt-1 w-full text-sm">
      <thead className="text-left text-text-muted">
        <tr>
          <th className="font-normal">Observed</th>
          {columns.map(([l]) => (
            <th key={l} className="font-normal">
              {l}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {points.slice(-20).map((p) => (
          <tr key={p.t}>
            <td className="tabular">{fmtTime(new Date(p.t).toISOString())}</td>
            {columns.map(([l, k]) => (
              <td key={l} className="tabular">
                {fmtNum(k(p))}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ChartFrame({
  title,
  summary,
  children,
  points,
  columns,
}: {
  title: string;
  summary: string;
  children: React.ReactNode;
  points: SnapshotPoint[];
  columns: Array<[string, (p: SnapshotPoint) => number | null]>;
}) {
  const [table, setTable] = useState(false);
  const id = useId();
  return (
    <figure className="mt-1">
      <svg role="img" aria-labelledby={id} viewBox={`0 0 ${W} ${H}`} className="h-11 w-full" preserveAspectRatio="none">
        <title id={id}>
          {title}: {summary}
        </title>
        {children}
      </svg>
      <figcaption className="flex items-center justify-between gap-2 text-sm text-text-muted">
        <span>{summary}</span>
        <button type="button" className="shrink-0 text-primary hover:underline" onClick={() => setTable((t) => !t)} aria-expanded={table}>
          {table ? 'Hide data' : 'Data table'}
        </button>
      </figcaption>
      {table ? <DataTable points={points} columns={columns} /> : null}
    </figure>
  );
}

export function Sparkline({ points, label }: { points: SnapshotPoint[]; label: string }) {
  const key = (p: SnapshotPoint) => p.pending;
  const { paths, max } = segments(points, key, false);
  const known = points.filter((p) => p.pending !== null);
  const last = known[known.length - 1]?.pending ?? null;
  const summary =
    known.length === 0
      ? 'No data since page load'
      : `Since page load: ${known.length} samples, latest ${fmtNum(last)}, peak ${fmtNum(Math.max(...known.map((p) => p.pending ?? 0)))}`;
  return (
    <ChartFrame title={label} summary={summary} points={points} columns={[['Pending', key]]}>
      <line x1="0" x2={W} y1={H - 2} y2={H - 2} stroke="var(--border)" strokeWidth="1" />
      {paths.map((d, i) => (
        <path key={i} d={d} fill="none" stroke="var(--accent)" strokeWidth="2" vectorEffect="non-scaling-stroke" />
      ))}
      <title>{`max ${max}`}</title>
    </ChartFrame>
  );
}

export function StepChart({ points }: { points: SnapshotPoint[] }) {
  const desiredKey = (p: SnapshotPoint) => p.desired;
  const readyKey = (p: SnapshotPoint) => p.ready;
  const both = (p: SnapshotPoint) => Math.max(p.desired ?? 0, p.ready ?? 0) || null;
  const s = scale(points, both);
  const d = segments(points, desiredKey, true);
  const r = segments(points, readyKey, true);
  void s;
  const known = points.filter((p) => p.desired !== null);
  const summary =
    known.length === 0
      ? 'No replica data since page load'
      : `Since page load: desired ${fmtNum(known[known.length - 1]?.desired)}, peak desired ${fmtNum(Math.max(...known.map((p) => p.desired ?? 0)))}`;
  return (
    <ChartFrame
      title="Desired vs ready replicas"
      summary={summary}
      points={points}
      columns={[
        ['Desired', desiredKey],
        ['Ready', readyKey],
      ]}
    >
      <line x1="0" x2={W} y1={H - 2} y2={H - 2} stroke="var(--border)" strokeWidth="1" />
      {d.paths.map((p, i) => (
        <path key={`d${i}`} d={p} fill="none" stroke="var(--primary)" strokeWidth="2" strokeDasharray="4 3" vectorEffect="non-scaling-stroke" />
      ))}
      {r.paths.map((p, i) => (
        <path key={`r${i}`} d={p} fill="none" stroke="var(--teal)" strokeWidth="2" vectorEffect="non-scaling-stroke" />
      ))}
    </ChartFrame>
  );
}
