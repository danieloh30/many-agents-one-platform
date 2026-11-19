# Demo runbook

A repeatable eight-minute live demonstration of three scenarios, with an optional fourth. The
runbook is event-neutral: reuse it for any talk slot that leaves about eight minutes for the demo.

Timings below are design targets. They become facts only after rehearsal on the actual cluster
(backlog items B-33 and B-39); record the measured values in the table in §7.

## 1. What the audience should take away

| Scenario | One-sentence message |
| --- | --- |
| 1 · Successful run | Agents are independent services coordinated through durable state and messages, and one trace shows the whole run. |
| 2 · Policy denial | The agent may ask; a separate service checks who is asking and what policy says before anything happens. |
| 3 · Queue burst | Agent workers scale on real backlog like any other workload. |
| 4 · Redelivery (optional) | A worker can fail mid-task and the effect is still committed once. |

## 2. Before the session

### T-1 day

1. `./demo.sh doctor` → all green. Resolve every `verify on cluster` item in [deployment.md](deployment.md) §8.
2. `./demo.sh up --platform openshift --profile summit --install-platform-components` (ends with smoke checks), then `./demo.sh verify`.
3. Rehearse all scenarios twice end to end; fill in §7.
4. Confirm the model provider key has quota and a rate limit of at least 12 concurrent requests.
5. Pre-pull: scale `evidence-agent` to 6 once so every node has the image, then let it return to 1.

### T-30 minutes

1. `./demo.sh reset` (clears runs, audit, queues; leaves the platform).
2. `./demo.sh verify --quick` → pass. Then `./demo.sh reset` again so the console starts clean (or use **Reset** in the presenter controls).
3. Open three browser tabs: **Console** (presenter mode `P`, full screen `F`), **Jaeger**, **Grafana →
   "Queue and scaling"** (**Agent platform** menu, or `/d/maop-queue-scaling`; auto-refresh 5 s, last 15 min).
4. In the console, open the presenter preflight panel: every component `OK`; model badge shows
   `LIVE <model>`; stream status `Live`.
5. If `MAOP_PRESENTER_AUTH=true`, enter the presenter token (print it with `./demo.sh token`; it is never shown in the UI). With the default `false` the controls are already enabled.
6. Check projector legibility in both themes; pick one and stay with it.
7. Terminal ready (large font) with: `oc get pods -n maop -l app.kubernetes.io/name=evidence-agent -w` — optional
   corroboration for scenario 3.

### Go / no-go

| Condition | Action |
| --- | --- |
| Model endpoint failing or slow (> 10 s per call in smoke) | Switch to deterministic mode (§6.1) and **say so** — the console shows the amber `DETERMINISTIC` badge. |
| KEDA or SPIRE not `OK` | Do not improvise on stage. Use the recorded fallback (§6.4) for the affected scenario. |
| Console unreachable from the venue network | Use the laptop hotspot; otherwise recorded fallback. |

## 3. Script (8:00)

| Time | Scenario | Action | What to point at |
| --- | --- | --- | --- |
| 0:00–0:30 | Orientation | Show the idle topology. | Five services, two queues, tool service with OPA, real replica counts, `SYNTHETIC DATA` badge. |
| 0:30–3:00 | **1 · Success** | Press **Run** on step 1 (or `Shift+1`). | See §3.1. |
| 3:00–4:45 | **2 · Policy denial** | Press **Run** on step 2. | See §3.2. |
| 4:45–7:30 | **3 · Queue burst** | Press **Run** on step 3. | See §3.3. |
| 7:30–8:00 | Wrap | Return to topology. | Replicas returning to 1; three runs in history, all durable. |

**How each scenario ends.** Every run that proposes an action stops in `AWAITING_APPROVAL` until a person decides.
Decide on stage, on the run's **Timeline** tab (the recommendation card), so no run is left waiting:

| Scenario | Decision | Final state | Why |
| --- | --- | --- | --- |
| 1 · Success | **Approve (simulate)** | `COMPLETED (REMEDIATION_SIMULATED)` | Shows the full path: a human approval lets policy allow the action, recorded as simulated. |
| 2 · Policy denial | **Approve (simulate)** | `COMPLETED (REMEDIATION_SIMULATED)` | The agent was denied acting alone; with a person's approval the same action is allowed. Both calls stay in the audit. |
| 3 · Queue burst | none | `COMPLETED (EVIDENCE_ONLY)` | Burst runs gather evidence and stop; there is nothing to approve. |
| 4 · Redelivery | **Approve (simulate)** (or leave it) | `COMPLETED (REMEDIATION_SIMULATED)`, or `APPROVAL_EXPIRED` after 10 min | A run that survived a worker failure still finishes end to end. The simulated action is a separate `SIMULATED` row; the redelivered call keeps its "Requested 2×" tag. |

### 3.1 Scenario 1 — successful run with connected traces (2:30)

1. Run the **Success** preset. The run appears as `SUBMITTED` → `TRIAGING`.
2. Topology: triage calls the model, then publishes evidence tasks; edges pulse as real events arrive.
3. Evidence agents call the tool service over mTLS — hover an edge to show the verified SPIFFE ID.
4. Open the run timeline: state rail reaches `RECOMMENDING` then `AWAITING_APPROVAL`
   (expected 20–40 s with a live model).
5. Inspector → **Evidence**: each finding lists facts linked to tool invocations.
6. Inspector → **Summary**: the proposed action is a structured record with citations — a proposal,
   not an execution.
7. Click **Open trace in Jaeger**: one trace, spans from console, triage, NATS publish/process,
   evidence agents, tool service (with policy decision attributes), recommendation agent.

8. Click **Review & decide** in the amber *Your decision is needed* bar (or scroll the **Timeline** tab to the
   recommendation card), click **Approve (simulate)** and confirm. The run moves
   `EXECUTING_ACTION` → `COMPLETED (REMEDIATION_SIMULATED)`; **Tool audit** shows the simulated action with its
   `SIMULATED` badge. Nothing changes on any cluster.

Say: the run state lives in PostgreSQL, not in any agent's memory; a restart of any service would not lose it.
The approval is bound to the exact proposed action (its digest), so it cannot be reused for a different one.

### 3.2 Scenario 2 — unapproved remediation denied (1:45)

1. Run the **Policy denial** preset. Explain the one difference: the recommendation agent is
   configured to try to execute its proposal without approval.
2. Wait for the red denied edge between recommendation agent and tool service (expected 20–40 s).
3. Inspector → **Policy**: decision `DENIED`, verified caller
   `…/sa/recommendation-agent`, reason `approval_required`, OPA decision ID, policy revision.
4. Inspector → **Tool audit**: "No side effect — denied before execution".
5. Run state: `AWAITING_APPROVAL` with `policyDenials: 1` — the workflow fell back to the safe path.
6. On the **Timeline** tab, read the note under the denial ("Why this run differs from a normal run"), click
   **Approve (simulate)** and confirm. The run ends `COMPLETED (REMEDIATION_SIMULATED)`. **Tool audit** now shows the
   same rollback twice: first `DENIED` (no approval, no side effect), then allowed and `SIMULATED` with the approval.

Say: identity came from the TLS connection, the decision from OPA, and the agent could not have
supplied either. The agent was right about what to do, but not allowed to do it on its own; once a person approves,
the same action goes through. Policy denied acting *without approval*, not the action itself.

Same data as scenario 1: the incident, the synthetic tool results and the proposal are identical. The only
difference is the run's remediation mode, `ATTEMPT_UNAPPROVED`, which makes the recommendation agent try its proposal
before anyone approves it.

If the model proposes a no-side-effect action (`ESCALATE_TO_HUMAN`/`OBSERVE`), no remediation is
attempted and nothing is denied. Say so, and rerun once; if it repeats, continue to scenario 3
(§6.2).

### 3.3 Scenario 3 — bounded queue burst and scale-out (2:45)

1. Look at the operations strip at the bottom (it opens its tiles by itself when the burst starts; **Show details**
   opens them earlier); note queue depth 0, replicas `1 / 1`.
2. Run the **Burst** preset (creates a capped batch of preset-planned evidence runs — the console
   shows the exact count and the `planSource: PRESET` tag).
3. Queue depth and queue age rise immediately.
4. Within the rehearsed scale-out time (design target ≤ 45 s): desired replicas step up; new pod
   pills appear `Pending` → `Running` → `Ready`; in-flight count rises. While new pods start, the evidence-agent
   node shows **Scaling up**, not Degraded; Degraded is kept for pods that crash or fail to pull their image.
5. Queue drains; queue age falls to 0.
6. Show Grafana "Queue and scaling" for the same curve from Prometheus — two independent views
   of the same real signal. The console's Grafana button opens *Run overview*; switch with the **Agent platform**
   menu at its top left (or open `<grafana>/d/maop-queue-scaling`). Point at *Evidence-agent replicas (desired vs
   ready)* and *NATS exporter: consumer pending (cross-check)*.
7. Scale-in begins after the stabilization window; it may complete after the demo ends. Say so
   rather than waiting.

Say: KEDA scales the worker Deployment on consumer lag; the upper bound is deliberate because every
replica can call the model.

There is nothing to approve: burst runs stop after evidence, as `COMPLETED (EVIDENCE_ONLY)`. While the burst is
active, the burst scenario's **Run** button is disabled and **Reset** is refused. On the local stack (no Kubernetes or KEDA) the replica
tiles show Unavailable; show the queue instead: one worker with two model calls in flight drains the backlog
slowly, which is the problem scale-out solves.

### 3.4 Scenario 4 — redelivery without a duplicate effect (optional, 1:30)

Only when the slot allows it (for example a longer session).

1. Run the **Redelivery** preset. The run header shows the amber tag `Fault injected: DROP_ACK_ONCE`.
2. In the task lanes, one evidence task shows `delivery 1` ending without a result, then an amber
   `delivery 2` after the acknowledgement timeout (about 20 s), and a **Redelivered** tag. The other
   evidence lanes have one delivery.
3. Under that lane: "requested 2 times, executed once". Click **Open in Tool audit** to show the invocation
   (`requests: 2`, `executions: 1`, second response `REPLAYED`).
4. The run itself carries on and reaches `AWAITING_APPROVAL`: the abandoned delivery cost time, not correctness.
   On the **Timeline** tab, click **Approve (simulate)**; the run ends `COMPLETED (REMEDIATION_SIMULATED)`. In
   **Tool audit** the simulated rollback is its own `SIMULATED` row, separate from the redelivered call tagged
   "Requested 2×". When time is short, leave it instead: it ends `APPROVAL_EXPIRED` after 10 minutes.

## 4. Presenter controls

| Key | Action |
| --- | --- |
| `P` / `F` | Presenter mode / full screen |
| `Shift+1…4` | Run scenario 1–4 |
| `T` | Toggle topology / timeline |
| `[` `]` | Previous / next run |
| `G` | Fold / unfold the guide (presenter mode) |
| `L` / `I` | Hide / show the left rail / the inspector |
| `?` | Shortcut help |

The guide bar shows one scenario at a time: click a step chip (or press `Shift+1…4`, which also runs it) to switch,
and fold the guide with `G` when you want the whole stage for the topology. Its checklist ticks only when the real
event arrives; an unticked item means it has not happened yet.

## 5. Reset between rehearsals

`./demo.sh reset` (or **Reset** in the console's top bar, also in the presenter bar), then reload the console. Reset takes under 30 s and leaves platform components and
images in place. Do not reset on stage.

## 6. Failure handling on stage

### 6.1 Model endpoint unavailable

Set `MAOP_MODEL_MODE=deterministic` in `.env.local` and run `./demo.sh up --skip-build` (about a minute). The console shows `DETERMINISTIC` in the top bar and on every model-derived
card. State it out loud. Policy, identity, messaging, tracing, and scaling remain fully real.

### 6.2 A scenario does not produce the expected result

Do not retry more than once. Name what happened using the console (it shows the real state and
reason), then move on. A run in `NEEDS_REVIEW` with a visible reason is itself an example of the
bounded-retry path.

### 6.3 Scale-out is slow

Keep talking over the Grafana panel; show `oc get pods -w` in the terminal. Do not claim replicas
that are not shown.

### 6.4 Recorded fallback

Record a clean run of each scenario during rehearsal (screen capture of the real system). If a
platform component is down at go/no-go, play the recording and say that it is a recording.

## 7. Rehearsal log (fill in on the target cluster)

| Measure | Design target | Measured (date, cluster) |
| --- | --- | --- |
| Scenario 1 time to `AWAITING_APPROVAL` | 20–40 s | 35 s (Red Hat OpenShift sandbox, live gpt-4o-mini, `./demo.sh verify`) |
| Scenario 2 time to denial | 20–40 s | |
| Scenario 3 time from burst to first extra pod `Ready` | ≤ 45 s | desired 1 → 5 after ~18 s, 6 after ~29 s; ready replicas lag pod start (same run) |
| Scenario 3 time to drain | ≤ 120 s | |
| Scenario 3 time back to 1 replica | ≤ 180 s after drain | |
| Scenario 4 redelivery delay | ≈ 20 s | |
| Model calls / tokens consumed per full demo | — | |
