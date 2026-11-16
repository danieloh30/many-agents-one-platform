# Many Agents, One Platform

[![Deploy docs to GitHub Pages](https://github.com/danieloh30/many-agents-one-platform/actions/workflows/pages.yml/badge.svg)](https://github.com/danieloh30/many-agents-one-platform/actions/workflows/pages.yml)

A reference demonstration of **operationally maintainable Java multi-agent services** on
Kubernetes and OpenShift, built from CNCF building blocks. An incident assistant investigates
increased checkout errors after a release — and the interesting part is everything around the
model calls: durable workflow state, asynchronous work with bounded retries, verified workload
identity, runtime policy enforcement, connected telemetry, and demand-driven scaling.

> **Status:** implemented and verified end to end on OpenShift 4.22 and on a local stack, with a live model.
> See [Verification status](#verification-status) and the [tutorials](https://danieloh30.github.io/many-agents-one-platform/).
> This is a demonstration, not a production system.

## What it demonstrates

| Scenario | What you see |
| --- | --- |
| Successful run | Triage, evidence, and recommendation agents — separate services — complete a durable run; one trace connects every hop. |
| Policy denial | An agent attempts a simulated remediation without approval; the tool service verifies its identity and policy denies the call before anything executes. |
| Queue burst | A bounded burst of evidence work raises queue lag; evidence workers scale out and back. |
| Redelivery (optional) | A worker abandons a task after its tool call commits; the task is redelivered and the effect is still recorded once. |

Tool data is synthetic and remediation is simulated — the system cannot change a real cluster.
Model calls are real by default; a deterministic substitute exists for tests and is always labelled.

## Architecture at a glance

```mermaid
%%{init: {"look":"handDrawn","theme":"base","themeVariables":{"darkMode":false,"background":"#FFFCF5","primaryColor":"#D4E6F1","primaryBorderColor":"#2E6B8A","primaryTextColor":"#1F1F1F","secondaryColor":"#D8F0D8","secondaryTextColor":"#1F1F1F","tertiaryColor":"#E8DCC4","tertiaryTextColor":"#1F1F1F","textColor":"#1F1F1F","lineColor":"#4A4035","edgeLabelBackground":"#FFFCF5","clusterBkg":"#F5F5F0","clusterBorder":"#6B5B45","fontSize":"16px"},"flowchart":{"nodeSpacing":35,"rankSpacing":55,"padding":18,"curve":"basis"}}}%%
flowchart LR
    USER([Operator browser]) --> CONSOLE([ops-console<br/>Agent Operations Console])
    CONSOLE -->|REST| TRIAGE

    subgraph AGENTS[Agent services]
        TRIAGE[triage-agent<br/>owns the durable run]
        EVIDENCE[evidence-worker x N]
        RECO[recommendation-agent]
    end

    AGENTS <-->|CloudEvents| NATS[(NATS JetStream<br/>work · events · DLQ)]
    EVIDENCE -->|mTLS · SPIFFE ID| TOOLS
    RECO -->|mTLS · SPIFFE ID| TOOLS

    subgraph BOUNDARY[Protected tool boundary]
        TOOLS[tool-service<br/>enforces] -->|allow / deny| OPA[OPA<br/>decides]
    end

    TRIAGE --> PG[(PostgreSQL<br/>runs · audit · outbox)]
    TOOLS --> PG
    KEDA([KEDA]) -.->|scales on queue lag| EVIDENCE
    AGENTS -.->|OpenTelemetry| OBS([Collector → Jaeger<br/>Prometheus · Grafana])

    style USER fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style CONSOLE fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style AGENTS fill:#F5F5F0,stroke:#8B8070,color:#1F1F1F,stroke-width:2px
    style TRIAGE fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style EVIDENCE fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style RECO fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style NATS fill:#E8E0F0,stroke:#6B5B8A,color:#1F1F1F,stroke-width:2px
    style PG fill:#E8E0F0,stroke:#6B5B8A,color:#1F1F1F,stroke-width:2px
    style BOUNDARY fill:#F5F5F0,stroke:#8B8070,color:#1F1F1F,stroke-width:2px
    style TOOLS fill:#D8F0D8,stroke:#3D7A3D,color:#1F1F1F,stroke-width:2px
    style OPA fill:#F8D7D0,stroke:#A34736,color:#1F1F1F,stroke-width:2px
    style KEDA fill:#E8DCC4,stroke:#6B5B45,color:#1F1F1F,stroke-width:2px
    style OBS fill:#E8DCC4,stroke:#6B5B45,color:#1F1F1F,stroke-width:2px
```



| Building block | Role |
| --- | --- |
| Java 25, Quarkus, Quarkus LangChain4j | Agent services with structured model outputs |
| React, TypeScript, Vite | Agent Operations Console |
| PostgreSQL | Durable run records, audit, outbox/inbox |
| NATS JetStream + CloudEvents | Durable asynchronous work and events |
| OpenTelemetry, Collector, Jaeger, Prometheus, Grafana | Traces, metrics, dashboards |
| SPIFFE/SPIRE | Workload identity on the mTLS connection to the tool service |
| Open Policy Agent | Runtime authorization at the tool service |
| KEDA | Scaling evidence workers on queue lag |
| Kubernetes, OpenShift | Deployment targets |

## Documentation

| Document | Contents |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | Services, ownership, run state machine, reliability model, sequence diagrams |
| [docs/contracts.md](docs/contracts.md) | Versioned REST and event contracts |
| [docs/security.md](docs/security.md) | Identity, authorization, enforcement flow, hardening |
| [docs/deployment.md](docs/deployment.md) | Manifests, kind and OpenShift profiles, prerequisites |
| [docs/ui-design.md](docs/ui-design.md) | Console design |
| [docs/demo-runbook.md](docs/demo-runbook.md) | Step-by-step live demonstration |
| [docs/dependency-maintenance.md](docs/dependency-maintenance.md) | Automated upgrades and compatibility gates |
| [docs/versions.md](docs/versions.md) | Pinned version set |
| [docs/decisions/](docs/decisions/README.md) | Architecture decision records |
| [IMPLEMENTATION_HANDOFF.md](IMPLEMENTATION_HANDOFF.md) | Ordered implementation backlog with acceptance criteria |

## Requirements

- JDK 25 or newer (Maven Wrapper included), Node.js 24 LTS or newer
- For OpenShift: `oc` logged in as a cluster administrator, `helm`; images are built in-cluster (no Docker Desktop)
- For other Kubernetes clusters: `kubectl`, `helm`, podman or docker, and a registry the cluster can pull from
- For the local stack: podman or docker, `openssl`, `jq`, `curl`
- An OpenAI-compatible model endpoint. Credentials stay server-side.

## Configuration

Copy `.env.example` to the git-ignored `.env.local`. Variables already in the environment take precedence.

| Variable | Default | Purpose |
| --- | --- | --- |
| `MAOP_MODEL_MODE` | `live` | `live` or `deterministic` (scripted, always labelled in the console) |
| `MAOP_MODEL_BASE_URL` | `https://api.openai.com/v1/` | OpenAI-compatible endpoint |
| `MAOP_MODEL_NAME` | `gpt-4o-mini` | Model name |
| `MAOP_MODEL_API_KEY` | value of `OPENAI_API_KEY` | Secret; stored only in the `model-credentials` Secret |
| `MAOP_NAMESPACE` | `maop` | Dedicated namespace |
| `MAOP_EVIDENCE_MAX_REPLICAS` | `6` | KEDA ceiling for evidence workers (respect model rate limits) |
| `MAOP_SPIRE_TRUST_DOMAIN` | detected / cluster apps domain | Needed with an existing SPIRE whose trust domain cannot be detected |
| `MAOP_EXPOSE_OBSERVABILITY` | `true` | Routes/Ingresses for Jaeger and Grafana |
| `MAOP_REGISTRY` | — | `--platform kubernetes` only |

See `.env.example` for the remaining optional variables.

## Kubernetes (Recommended OpenShift)

On OpenShift, images are built in the cluster (BuildConfigs) and exposed through Routes. On any other
Kubernetes cluster, use `--platform kubernetes`: images are pushed to `MAOP_REGISTRY` with podman or docker and
exposed through Ingresses on `MAOP_INGRESS_DOMAIN`.

```bash
./demo.sh doctor
```

```bash
./demo.sh up --platform openshift --profile summit --install-platform-components
```

Any other Kubernetes cluster:

```bash
./demo.sh up --platform kubernetes --profile summit --install-platform-components
```

```bash
./demo.sh verify
```

```bash
./demo.sh open
```

Other commands: `./demo.sh build`, `status`, `logs [service]`, `token` (prints the presenter token), `reset`
(demonstration state only), `down` (keeps PVCs and Secrets unless `--delete-data`; removes project-installed
platform components only with `--uninstall-platform-components`). Without `--install-platform-components`,
`up` reuses existing KEDA/SPIRE (or the Custom Metrics Autoscaler) and fails with an actionable message if they
are missing. Details: [docs/deployment.md](docs/deployment.md).

## Fully local Kubernetes (kind)

Runs the complete runbook locally, including KEDA scale-out and SPIRE-issued identities, on a kind cluster with
a local registry. The cluster uses its own kubeconfig (`.maop/kind/kubeconfig`); your default context is not
changed. Requires kind (for example `brew install kind`) and podman or docker with about 8 CPUs and 16 GB.

```bash
scripts/local/kind.sh up
```

```bash
scripts/local/kind.sh verify
```

```bash
scripts/local/kind.sh down
```

Use `eval "$(scripts/local/kind.sh env)"` before `./demo.sh status`, `open`, `logs` or `token`. On kind the
NetworkPolicies are rendered off (`MAOP_NETWORK_POLICIES=false`): with kindnet's policy enforcement the
application pods could not resolve DNS in testing, and the root cause was not identified. OpenShift enforces the
same policies without issues.

## Local stack (no cluster)

```bash
./mvnw -B package -DskipTests && (cd web && npm ci && npm run build) && ./mvnw -B -pl ops-console package -DskipTests
```

```bash
scripts/local/stack.sh up
```

```bash
scripts/local/stack.sh e2e
```

| UI | URL |
| --- | --- |
| Agent Operations Console | http://localhost:8080 |
| Jaeger | http://localhost:16686 |
| Grafana (project dashboards, anonymous viewer) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

The presenter token is written to `.maop/local/presenter-token`. The model is deterministic by default here; run
`MAOP_MODEL_MODE=live scripts/local/stack.sh up` to use the configured live model.

The local stack runs the runbook's successful run, policy denial, queue burst (depth, age and drain) and
redelivery scenarios, with traces in Jaeger and metrics in Grafana. Not available without a cluster: KEDA
scale-out (replica tiles show "Unavailable"), SPIRE-issued SVIDs (a local CA issues the certificates; the console
shows identity `DEV_STATIC`), and OpenShift Routes and builds.

For single-service development, `./mvnw -pl <service> quarkus:dev` starts Quarkus Dev Services, including
PostgreSQL where needed and the Grafana LGTM stack (Grafana, Prometheus, Tempo, Loki) for that service. The
Grafana URL is in the log and the Dev UI. NATS is not a Quarkus Dev Service; use the local stack's NATS on
port 4222.

## Tests

```bash
./mvnw -B verify
```

```bash
cd web && npm ci && npm run typecheck && npm run lint && npm test && npm run build
```

Backend tests use real PostgreSQL, NATS and OPA containers (Testcontainers) and a deterministic model.
Live-model tests are separate: `./mvnw -B verify -Plive-model` or the `live-model-check` workflow.

## Verification status

| Area | Status |
| --- | --- |
| Java build and tests (JDK 25) | Pass locally |
| SPA typecheck, lint, tests, build | Pass locally |
| OPA policy tests | Pass locally, 100 % coverage |
| Local end-to-end (5 services, real NATS/PostgreSQL/OPA/Jaeger, mTLS) | Pass with the deterministic model and with live `gpt-4o-mini` |
| OpenShift 4.22 (`./demo.sh up` + `./demo.sh verify`) | All six checks pass: success run, one trace across six services, policy denial, SPIRE identity enforcement, KEDA scale-out 1 → 6, redelivery without a duplicate effect |
| kind (`scripts/local/kind.sh`) | All six checks pass (KEDA scale-out 1 → 5, SPIRE identities, live model), with NetworkPolicies off |
| GitHub Actions | `compat-gate` green; Dependabot, auto-merge classification and GitHub Pages deploy run on GitHub |

## Safety notes

- No real remediation: remediation tools write a simulated record only.
- No fabricated operational data: the console shows "Unavailable" when a source cannot be read.
- No hidden reasoning: only structured decisions and concise explanations are stored or displayed.
