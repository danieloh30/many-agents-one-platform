# Pinned version set

Single source of truth for versions in the design. Checked against Maven Central, the Quarkus
registry, npm, GitHub releases, and image registries on **2026-11-15**. During implementation
these values move into `pom.xml`, `web/package.json`, Dockerfiles, `deploy/**`, and
`deploy/platform/versions.env`; after that those files are authoritative and this page is kept in
step by the upgrade workflow ([dependency-maintenance.md](dependency-maintenance.md)).

Rule: images are referenced by tag **and digest** in manifests. Digests are resolved at
implementation time (they are not recorded here because they were not pulled in the design phase).

## 1. Java services

| Item | Version | Notes |
| --- | --- | --- |
| JDK | **25** (Temurin 25 LTS for build; `maven.compiler.release=25`) | Baseline. Never lower. No `--enable-preview`. |
| Maven | 3.9.16 via Maven Wrapper (`.mvn/wrapper/maven-wrapper.properties`) | Quarkus supports `[3.9.0,)`. |
| Quarkus platform BOM | `io.quarkus.platform:quarkus-bom:3.40.1.2` | 3.40 is the current stream and an LTS. 4.0 is a release candidate — not used. |
| Quarkus Maven plugin | `io.quarkus.platform:quarkus-maven-plugin:3.40.1.2` | Shares the `quarkus.platform.version` property with the BOM. |
| Quarkus LangChain4j | `io.quarkus.platform:quarkus-langchain4j-bom:3.40.1.2` → `io.quarkiverse.langchain4j:*` **1.13.4** (LangChain4j 1.19.3) | Taken from the platform member BOM, same property. The newer standalone 1.15.1 is intentionally not used. |
| Model provider extension | `io.quarkiverse.langchain4j:quarkus-langchain4j-openai` | Any OpenAI-compatible endpoint. |
| NATS client | `io.nats:jnats:2.26.4` | Own property `jnats.version` (independently versioned). |
| CloudEvents | In-repo envelope and NATS binary-binding codec (`maop-common`) | The SDK is not used: the Quarkus BOM pins `cloudevents-api` 3.0.0 (ADR-0016). |
| Test PKI | `org.bouncycastle:bcpkix-lts8on:2.73.12` (test scope only) | LTS line matching the `bcprov-lts8on` that jnats brings in. |
| Managed by the Quarkus BOM (no explicit version) | `quarkus-rest`, `quarkus-rest-jackson`, `quarkus-rest-client-jackson`, `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-scheduler`, `quarkus-smallrye-fault-tolerance`, `quarkus-smallrye-health`, `quarkus-opentelemetry`, `quarkus-micrometer`, `quarkus-micrometer-registry-prometheus`, `quarkus-tls-registry`, `quarkus-kubernetes-client` (ops-console only) | OpenTelemetry 1.62.0, Hibernate ORM 7.4.9, Flyway 12.0.0, Testcontainers 2.0.5 come with the BOM. |
| Runtime base image | `registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24` | JVM mode. Native image is out of scope. |

Java 25 status: Quarkus 3.40 builds on JDK 25 in its own CI and lists 25 among supported LTS
releases; full Java 25 support arrived in 3.31. Extensions are compiled for release 17, which is
compatible.

## 2. Frontend

| Package | Version | Notes |
| --- | --- | --- |
| Node.js | 24 LTS (≥ 24.15; 24.22.3 at check time) | `.nvmrc` + `engines`. Satisfies every tool below. |
| npm | Bundled with Node 24 | `package-lock.json` committed; `npm ci` only. |
| react / react-dom | 19.3.1 | |
| typescript | **~6.0.3** | Not 7.x: `typescript-eslint` 8.72 requires `<6.1.0`. |
| vite / @vitejs/plugin-react | 8.3.8 / 6.3.0 | |
| vitest / jsdom | 5.1.0 / 31.0.1 | |
| @testing-library/react / dom / jest-dom / user-event | 16.3.3 / 10.4.2 / 7.0.1 / 14.6.11 | |
| @playwright/test / @axe-core/playwright | 1.65.1 / 4.14.0 | |
| eslint / typescript-eslint / eslint-plugin-react-hooks | 10.15.0 / 8.72.2 / 7.1.1 | |
| @xyflow/react | 12.12.1 | Topology graph. |
| @tanstack/react-query | 5.107.1 | |
| react-router | 8.5.1 | |
| zustand | 5.1.0 | |
| tailwindcss / @tailwindcss/vite | 4.3.5 | |
| radix-ui | 1.8.1 | Unified package. |
| lucide-react | 1.73.0 | |
| zod | 4.6.7 | |
| @fontsource-variable/inter / jetbrains-mono | 5.3.0 | Self-hosted fonts. |

## 3. In-namespace infrastructure (plain manifests, maintained by Dependabot)

| Component | Version | Image |
| --- | --- | --- |
| NATS server (JetStream) | 2.15.3 | `docker.io/library/nats:2.15.3-alpine` |
| NATS Prometheus exporter | 0.20.3 | `docker.io/natsio/prometheus-nats-exporter:0.20.3` |
| nats-box (setup Job, diagnostics) | 0.20.2 | `docker.io/natsio/nats-box:0.20.2` |
| PostgreSQL | 18 | `quay.io/sclorg/postgresql-18-c9s:20261111` (runs under arbitrary UIDs) |
| Open Policy Agent | 1.22.4 | `docker.io/openpolicyagent/opa:1.22.4-static` |
| spiffe-helper | 0.12.3 | `ghcr.io/spiffe/spiffe-helper:0.12.3` |
| OpenTelemetry Collector (contrib) | 0.165.0 | `ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.165.0` |
| Jaeger v2 | 2.23.0 | `docker.io/jaegertracing/jaeger:2.23.0` |
| Prometheus | 3.16.0 | `docker.io/prom/prometheus:v3.16.0` |
| Grafana OSS | 13.3.1 | `docker.io/grafana/grafana:13.3.1` |
| curl (identity probe in `demo.sh verify`) | 8.23.0 | `docker.io/curlimages/curl:8.23.0` |

## 4. Cluster-scoped platform (Helm, pinned in `deploy/platform/versions.env`)

| Component | Chart | App version | Notes |
| --- | --- | --- | --- |
| KEDA | `keda` **2.21.0** from `https://kedacore.github.io/charts` | 2.21.0 | 2.21.1 was published on the check date with no chart yet; stay on 2.21.0 until a chart exists. Tested with Kubernetes 1.34–1.36. |
| SPIRE | `spire` **0.30.4** + `spire-crds` **0.6.1** from `https://spiffe.github.io/helm-charts-hardened/` | SPIRE 1.15.5, controller-manager 0.8.0 | SPIRE 1.16.0 exists but is not yet the chart default; keep chart defaults. Pin the SPIFFE CSI driver image tag explicitly (chart default trails the 0.3.0 release). |

## 5. Clusters

| Target | Version | Notes |
| --- | --- | --- |
| OpenShift | **4.22** (Kubernetes 1.35); minimum 4.21 (Kubernetes 1.34) | Inside KEDA 2.21's tested window. |
| Local Kubernetes | kind 0.33.0 with `kindest/node:v1.35.8` | Same Kubernetes minor as OpenShift 4.22. |
| Kustomize | The version bundled in `oc`/`kubectl` (5.7.x for 1.35) | No standalone install. |

## 6. GitHub Actions

`actions/checkout@v7`, `actions/setup-java@v6` (Temurin 25), `actions/setup-node@v7`,
`actions/cache@v6`, `actions/upload-artifact@v7`, `dependabot/fetch-metadata@v3`,
`peter-evans/create-pull-request@v8`, `docker/build-push-action@v7`, `github/codeql-action@v4`.
Workflows pin actions by commit SHA with the version in a trailing comment; Dependabot maintains them.

## 7. CNCF status of the building blocks (at check time)

| Project | Status |
| --- | --- |
| Kubernetes, Prometheus, Jaeger, OPA, SPIFFE, SPIRE, KEDA, NATS, OpenTelemetry, CloudEvents | Graduated |
| Agent Substrate (optional, future) | Sandbox (accepted by TOC vote; onboarding in progress) |
| Grafana, PostgreSQL, Quarkus LangChain4j | Not CNCF projects |

Do not state CNCF maturity levels in talks or docs without re-checking the
[CNCF landscape](https://landscape.cncf.io/); they change.

## 8. Compatibility risks carried by this set

| # | Risk | Handling |
| --- | --- | --- |
| V1 | Platform LangChain4j (1.13.4) trails the standalone release (1.15.1). | Accepted for stability; never mix the two BOMs. |
| V2 | `jnats` and CloudEvents are outside the platform BOM. | Thin adapter in `maop-common`, integration tests against a real NATS container. |
| V3 | TypeScript must stay on 6.0.x until `typescript-eslint` supports 7. | Dependabot `ignore` for TypeScript majors; revisit in the compatibility check. |
| V4 | Helm chart versions trail application releases (KEDA, SPIRE). | Charts are pinned, not app versions; the scheduled check proposes chart bumps. |
| V5 | OpenShift's Custom Metrics Autoscaler operator and upstream KEDA cannot coexist, and the operator does not list the NATS JetStream trigger. | See [deployment.md](deployment.md) §5.1. Needs cluster verification. |
| V6 | Quarkus platform versions have four parts (`3.40.1.2`); Dependabot's patch/minor classification of them is not confirmed. | Auto-merge is gated on the compatibility checks, not on the classification alone; stream changes are owned by the scheduled workflow. |
| V7 | A native Quarkus SPIFFE client exists only in the 4.0 pre-release line. | Use `spiffe-helper` + TLS registry now; revisit when 4.x is the LTS (ADR-0008). |
