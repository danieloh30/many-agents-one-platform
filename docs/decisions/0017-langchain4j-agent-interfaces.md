# 0017 — Agents as LangChain4j `@Agent` interfaces behind the model gateway

**Status:** Accepted. Supersedes ADR-0016 item 2 (direct `ChatModel` calls) for the three agents and relaxes
ADR-0002's "agentic module not used".

## Context
The three agents were plain classes calling the provider `ChatModel` through `ModelGateway`. LangChain4j's
agentic module (`dev.langchain4j.agentic.Agent`, managed by the Quarkus platform member BOM as
`langchain4j-agentic` 1.19.3-beta29 via `quarkus-langchain4j-agentic` 1.13.4) offers declarative agents that are
CDI beans with typed outputs. The module is beta upstream, and its workflow annotations (`@SequenceAgent`,
`@SupervisorAgent`, ...) run in-process, which would lose runs on pod restarts.

## Decision
- `IncidentTriageAgent`, `EvidenceAnalystAgent` and `RemediationAdvisorAgent` are `@Agent` interfaces with
  `@SystemMessage`/`@UserMessage` templates and typed record outputs (`TriagePlan`, `EvidenceFinding`,
  `RecommendationDraft`).
- Each agent's `@ChatModelSupplier` returns `GatewayChatModel`, which delegates to `ModelGateway`. The task's
  deadline and budget are passed with `AgentInvocation.call(...)`, so per-pod permits, deadline-derived timeouts,
  budgets, provider-reported token metrics, spans and the labelled deterministic mode are unchanged.
- Domain validation stays in the agent classes (`TriagePlanner`, `EvidenceAgent`, `RecommendationAgent`), with one
  bounded repair attempt that feeds the validation error back to the agent.
- Agentic workflow annotations are not used: cross-pod orchestration stays durable (JetStream + PostgreSQL).
- Protected tool calls stay in worker code (deterministic, SPIFFE mTLS, OPA, idempotent); the evidence agent no
  longer requests follow-up tools itself.

## Consequences
- Declarative, injectable agents with typed outputs; prompts live with the agent interface.
- Dependency on a beta upstream module, pinned through the platform BOM; upgrades are reviewed via the upgrade
  workflow.
- `AgentGatewayTest` proves the gateway path (budget, deadline, metrics) for `@Agent` calls.
