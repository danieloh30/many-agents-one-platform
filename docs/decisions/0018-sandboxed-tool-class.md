# 0018 — A sandboxed tool class with a simulated executor

**Status:** Accepted

## Context
[ADR-0012](0012-agent-substrate-optional-future.md) keeps Agent Substrate out of the core demo and says that, if it
is integrated, it sits behind tool-service for selected sandboxed workloads. The demo had no workload of that kind,
so the idea could only be shown on a slide. The cluster the demo runs on (OpenShift) does not meet Agent Substrate's
prerequisites, so a real integration could not be demonstrated there.

## Decision
- Add a third tool class, `SANDBOXED`, and one tool in it, `analysis.run_script`. It runs a short analysis script
  over recent error logs of a service.
- Add the permission `tool.sandbox`. Only `evidence-agent` holds it. OPA decides before anything runs, as for every
  other tool. The class never needs an approval, because it has no effect outside the sandbox.
- tool-service runs sandboxed tools through a `SandboxExecutor`. The only implementation is
  `SimulatedSandboxExecutor`: it records the script, does not execute it, contacts no sandbox runtime, and returns
  the synthetic analysis marked `sandbox.mode = SIMULATED`, `sandbox.executed = false`.
- `maop.sandbox.mode` selects the executor. `SIMULATED` is the only accepted value; any other value fails at
  startup. There is no fallback from a real runtime to the simulation.
- A run asks for the tool with a new setting, `analysisMode = SANDBOXED_SCRIPT` (default `NONE`). Code adds one
  evidence task with the topic `LOG_ANALYSIS`; the model cannot plan that topic and never sees the tool. The script
  is fixed text in `evidence-agent`.
- A fifth console scenario, `SANDBOX`, sets that field. The four existing presets and their requests are unchanged.

## Consequences
- The enforcement path for sandboxed execution (identity, policy, idempotency, audit, trace) is real and tested.
  The sandbox itself is a labelled simulation, in the same way remediation is.
- tool-service still has no Kubernetes or cloud client, and no path can change a real cluster.
- A real runtime is one more `SandboxExecutor` plus an accepted value for `maop.sandbox.mode`. ADR-0012 still
  governs that step: it needs a cluster that meets the prerequisites, and its facts must be re-validated first.
- Runs with `analysisMode = SANDBOXED_SCRIPT` plan one evidence task fewer through the model, so the run's
  model-call budget is unchanged.
