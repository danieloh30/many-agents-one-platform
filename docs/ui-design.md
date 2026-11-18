# UI Design — Agent Operations Console

A single-page application (React, TypeScript, Vite) served by `ops-console`. It is an operator's
console, not a chat window: it shows what the platform did, which identity did it, what policy
decided, and what it cost. Versions are pinned in [versions.md](versions.md).

## 1. Principles

1. **Nothing on screen is invented.** Every number, status, and animation is derived from a backend
   event or API response. A source that cannot be read renders as "Unavailable" with the reason —
   never as zero, never as a green check, never as a last-known value without its age.
2. **Decisions, not thoughts.** The console shows structured outputs (classification, evidence
   findings, proposed action, policy decision) and their concise explanations. It has no "thinking"
   panel and never renders prompt or raw completion text.
3. **Provenance is always visible.** Synthetic tool data, simulated remediation, deterministic
   model mode, and fault injection are labelled wherever their results appear.
4. **Readable from the back of a room.** Presenter mode guarantees ≥ 18 px body text and ≥ 4.5:1
   contrast on a projector.
5. **Durable first.** Any view can be rebuilt from REST after a refresh; SSE only makes it live.
6. **Event-neutral.** No conference, session, or date text anywhere in the UI.

## 2. Information architecture

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ Top bar: product name · model badge (LIVE <model> | DETERMINISTIC) · SYNTHETIC DATA    │
│          · stream status · reset · theme · presenter · full screen                     │
├───────────────┬──────────────────────────────────────────────┬─────────────────────────┤
│ Left rail     │ Main stage                                   │ Inspector (right)       │
│               │                                              │                         │
│ Scenarios     │  Live topology (default)                     │ Tabs for the selected   │
│  · Success    │  — or —                                      │ run / node / edge:      │
│  · Policy     │  Run timeline                                │  Summary · Evidence ·   │
│  · Burst      │                                              │  Policy & identity ·    │
│  · Redelivery │                                              │  Tool audit · Usage     │
│ New incident  ├──────────────────────────────────────────────┤                         │
│ Recent runs   │ Operations strip: queue depth · queue age ·  │                         │
│               │ in-flight · replicas (desired/ready/max)     │                         │
└───────────────┴──────────────────────────────────────────────┴─────────────────────────┘
```

Routes: `/` (console), `/runs/:runId` (deep link, selects the run), `/runs/:runId/audit/:invocationId`.
Layout collapses to a single column under 1024 px (inspector becomes a drawer); the primary target
is 1920×1080 and 1280×720 projector output.

## 3. Views

### 3.1 Incident submission and scenario presets

- Four preset cards from `GET /api/v1/scenarios`: title, one-line promise ("Policy denies an
  unapproved rollback before it runs"), and the exact request it will send (expandable).
- "New incident" form: title, description, affected service (select from the synthetic catalogue),
  remediation mode. Client-side validation with zod mirrors server limits (title ≤ 120,
  description ≤ 2000).
- Submit generates an `Idempotency-Key` per form instance, so a double click cannot create two runs.
- The burst card shows the server-side cap and is disabled while a burst is active (`409 BURST_ACTIVE`).
- Mutations need the presenter token (entered once, kept in `sessionStorage`); without it the
  controls are visible but disabled with an explanatory tooltip — the audience can browse read-only.

### 3.2 Live topology

A fixed-layout graph (React Flow, positions hand-authored for legibility — no auto-layout jitter):

```
 Console ──▶ Triage agent ──▶ [ work queue: evidence ] ──▶ Evidence agents ×N ──┐
                 │  ▲                                                            ├─▶ Tool service ─▶ OPA
                 │  └──────────── results (events) ◀─────────────────────────────┤     (mTLS, SVID)
                 └─▶ [ work queue: recommendation ] ──▶ Recommendation agent ────┘
                          Model endpoint (external) ◀── agents
```

- **Nodes**: services (with ready/desired replicas from the ops snapshot), queues (pending /
  in-flight), tool service with OPA attached, model endpoint. Evidence agents render as one node
  with a pod strip: one pill per real pod (`Pending`, `Running`, `Ready`), added and removed as the
  snapshot changes.
- **Edges** animate only when a matching event arrives for the selected run (or any run when none
  is selected): a command published, a task started, a tool invocation, a model call. Animation is
  a single pulse per event; no idle shimmer.
- **Edge semantics** are encoded twice (colour + line style + icon): async message (dashed),
  mTLS call (solid with lock icon and the verified caller SPIFFE ID on hover), denied call (red,
  cross icon, stops before the tool node).
- Clicking a node or edge filters the inspector to that element. Keyboard: nodes are focusable in
  reading order; `Enter` selects.
- A text alternative ("Activity list") presents the same events as an ordered list for screen
  readers and is the fallback if the graph fails to render.

### 3.3 Run timeline and evidence inspection

- **State rail**: the durable states as a horizontal stepper
  (`SUBMITTED → TRIAGING → GATHERING_EVIDENCE → RECOMMENDING → AWAITING_APPROVAL → EXECUTING_ACTION → COMPLETED`),
  with terminal alternates (`FAILED`, `TIMED_OUT`, `NEEDS_REVIEW`, `CANCELLED`) shown when reached.
  Each step shows its entry time and duration. Source: `GET /runs/{id}/timeline`.
- **Deadline bar**: remaining time to the run deadline; turns amber under 20 %.
- **Task lanes**: one lane per task with delivery attempts drawn as segments, so a redelivery is
  visible as a second segment labelled `delivery 2` and a "Redelivered" tag. Under a redelivered lane, each tool
  call the tool audit recorded more than once is listed with its request and execution counts ("requested 2
  times, executed once") and a link to that invocation in *Tool audit*. Source: `GET /audit/invocations?runId=`.
- **Evidence cards**: topic, finding, confidence, and the facts list. Each fact links to the tool
  invocation that produced it (opens the audit tab). Badge: `SYNTHETIC`.
- **Recommendation card**: summary, concise explanation, proposed action as a structured table
  (type, target, parameters, action digest), citations as chips linking to evidence cards, risks.
  Buttons: **Approve (simulate)** and **Reject**, presenter-token gated, both with a confirmation
  step that restates "simulation only — no cluster change".
- **Trace link**: "Open trace in Jaeger" → `<jaegerBaseUrl>/trace/<traceId>`; disabled with a reason
  when `traceId` is missing or Jaeger is `UNAVAILABLE`.

### 3.4 Policy, identity, and tool audit

For each tool invocation (`GET /api/v1/audit/invocations`):

| Field | Presentation |
| --- | --- |
| Decision | `ALLOWED` / `DENIED` / `REPLAYED` pill (icon + text + colour) |
| Verified caller | SPIFFE ID, trust domain, SVID serial and expiry, "verified by mTLS" marker |
| Tool | Name, class (`READ` / `REMEDIATION`), `SIMULATED` badge |
| Policy | OPA decision ID, policy revision, the rule reasons (e.g. `approval_required`), evaluated input (redacted) in a collapsible JSON viewer |
| Idempotency | Key, `executions: 1`, `requests: 2` when replayed |
| Effect | For remediation: the `simulated_action` row, or "No side effect — denied before execution" |

A denial raises a non-blocking toast and marks the topology edge; it is not styled as an
application error — policy working is a success state for the operator.

### 3.5 Operations strip

Fed by `ops-snapshot` SSE events (2 s cadence):

- **Queue depth** (pending) and **in-flight** for `evidence-agents`, with a 5-minute sparkline
  built from snapshots received in this browser session (labelled "since page load").
- **Queue age**: age of the oldest unacknowledged message.
- **Replicas**: `ready / desired` with min–max range and the scaler's active flag; a step chart of
  desired vs ready.
- **Worker activity**: active tasks per pod from heartbeats.
- **Platform**: OPA, SPIRE, NATS, model endpoint, Jaeger, Prometheus/Grafana status dots with text.
- Links: "Grafana dashboard" → provisioned dashboard URL from `/api/v1/config`; disabled when the
  backend reports Grafana unreachable.

Each tile shows `observedAt` age; if older than 10 s it greys out and reads "stale 14 s".

### 3.6 Presenter controls and full-screen mode

- Toggle with `P`; full screen with `F` (Fullscreen API); `?` shows shortcuts.
- A slim guide bar lists the scenario steps (1 Success · 2 Policy denial · 3 Burst · optional
  Redelivery). Each step has: **Run** (fires the preset), an auto-advancing checklist of expected
  observations that tick only when the corresponding real event arrives
  (e.g. "✓ Tool call denied — decision `…`"), and **Focus** buttons that pan/zoom the stage to the
  relevant element. Nothing in the guide simulates an outcome.
- Presenter mode raises the type scale (§4.2), hides the left rail, and enlarges the stage.
- **Reset** (delete runs, audit, simulated actions and queued messages, after a confirm dialog) sits in the top
  bar outside presenter mode and in the guide bar in presenter mode; it is refused while a burst is active.
- `1`–`4` trigger presets (after a confirm chord `Shift`), `T` toggles topology/timeline,
  `[` / `]` move between runs. All shortcuts are listed in the help dialog and avoid browser defaults.
- A "preflight" panel (presenter only) runs `GET /api/v1/ops/snapshot` + `/config` and lists any
  component not `OK` before the talk.

## 4. Visual design

### 4.1 Colour tokens

Defined as CSS custom properties on `:root`, overridden by `[data-theme="dark"]`; theme follows
`prefers-color-scheme` until the user chooses, then persists in `localStorage`.

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| `--bg` | `#F5F8FC` | `#07122B` | App background (navy in dark) |
| `--surface` | `#FFFFFF` | `#0D1D40` | Cards, panels |
| `--surface-raised` | `#EDF2FA` | `#14295A` | Inspector, hover |
| `--border` | `#C9D5E8` | `#274079` | Dividers, node outlines |
| `--text` | `#0A1838` | `#EAF1FF` | Primary text |
| `--text-muted` | `#44557A` | `#A9BBDE` | Secondary text |
| `--primary` (electric blue) | `#1F5EFF` | `#5C8DFF` | Primary actions, active edges |
| `--accent` (cyan) | `#007C99` | `#22D3EE` | Live activity, focus ring |
| `--teal` | `#0B7A6B` | `#2DD4BF` | Allowed / healthy (restrained) |
| `--purple` | `#6B3FD4` | `#A78BFA` | Model calls, identity markers (restrained) |
| `--warn` | `#9A5B00` | `#FBBF24` | Deterministic mode, stale, fault injection |
| `--danger` | `#B3261E` | `#FF7A70` | Denied, failed |

Rules: navy and blue carry the structure; cyan marks liveness; teal and purple appear only as
small semantic accents (pills, icons, edge pulses), never as large fills. All text/background
pairs meet WCAG 2.2 AA (4.5:1 body, 3:1 large text and UI components); the palette is verified
with an automated contrast test in CI. Status is never conveyed by colour alone.

### 4.2 Typography

- UI: Inter Variable; identifiers, digests, SPIFFE IDs, JSON: JetBrains Mono Variable. Both are
  self-hosted (no runtime font CDN — conference Wi-Fi must not break the UI).
- Scale (rem): 0.875 / 1 / 1.125 / 1.375 / 1.75 / 2.25. Presenter mode multiplies the root size
  by 1.25 (body ≥ 20 px at 1080p). Tabular numerals for all metrics.
- Long identifiers truncate in the middle with full value on focus/hover and a copy button.

### 4.3 Motion

150–250 ms transitions; edge pulses ≤ 600 ms. `prefers-reduced-motion` replaces pulses with a
static highlight that fades. No looping animation anywhere.

## 5. Accessibility

- WCAG 2.2 AA target; semantic landmarks; all controls reachable and operable by keyboard with a
  visible 2 px cyan focus ring.
- Live updates use a polite `aria-live` region that announces state changes and policy decisions
  only (not every event).
- Charts have a text summary and an accessible data table toggle.
- Automated checks: axe in component tests and in the Playwright end-to-end run for both themes.

## 6. Frontend architecture

| Concern | Decision |
| --- | --- |
| Build | Vite, TypeScript `strict`, ES2022 target |
| Server state | TanStack Query for REST; one SSE connection managed by a small `EventStreamClient` that writes into the query cache and an event store |
| Client state | Zustand for UI state only (selection, theme, presenter mode) |
| Routing | React Router (data router not required) |
| Styling | Tailwind CSS reading the CSS variables above; Radix primitives for dialog, tabs, tooltip, toast |
| Graph | React Flow with custom node/edge components |
| Charts | Lightweight hand-built SVG sparkline and step chart components (two chart types do not justify a chart library) |
| API types | Generated from `contracts/openapi` at build time; zod validation at the SSE boundary |
| Tests | Vitest + Testing Library (components, reducers); Playwright (three scenarios against the deterministic stack; axe) |

SSE handling: native `EventSource` reconnects automatically and resends `Last-Event-ID`; on a
`reset` event or a gap in ids the client invalidates the affected queries. The stream-status
indicator in the top bar shows `Live`, `Reconnecting`, or `Offline — showing last loaded data`.

Source layout (`web/`): `src/app` (shell, routes, providers), `src/features/{scenarios,topology,runs,audit,ops,presenter}`,
`src/api` (generated types, clients, SSE), `src/design` (tokens, primitives), `e2e/`.

## 7. Empty, loading, and failure states

| Situation | Behaviour |
| --- | --- |
| No runs yet | Stage shows the idle topology with real replica counts and a "Run a scenario" prompt. |
| API unreachable | Blocking banner with retry; nothing is rendered from cache without a "stale" label. |
| Model endpoint failing | Run moves to `NEEDS_REVIEW`/`FAILED` with the server-provided reason; no substitute output is shown. |
| KEDA/SPIRE/OPA not installed or unhealthy | The relevant tile and node show `Unavailable` and the preflight panel lists it. |
| Deterministic model mode | Persistent amber top-bar badge and a badge on every model-derived card. |
| Fault injection on a run | Amber "Fault injected: DROP_ACK_ONCE" tag on the run header and a one-line explanation above the task lanes; only a lane that was actually redelivered gets the amber "Redelivered" tag. |
