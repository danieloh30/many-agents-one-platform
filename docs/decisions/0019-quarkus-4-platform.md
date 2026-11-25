# 0019 — Adopt Quarkus 4.0.1 after demo validation

**Status:** Accepted, conditional on the compatibility gate and Kubernetes demo smoke checks passing.

## Context

The owner requested Quarkus 4.0.1 even though the registry marks stream 4.0 as non-LTS.
ADR-0014 normally restricts automated stream discovery to LTS releases. A major upgrade also
changes Jackson, Jakarta REST, telemetry defaults, and the platform-managed LangChain4j extension.

## Decision

- Make a one-time exception to adopt platform 4.0.1, with the BOM, LangChain4j member BOM and
  Maven plugin sharing the existing property. Future stream discovery remains LTS-only.
- Apply the official Quarkus update recipes, including Jackson 3 and the Quarkus JUnit artifact rename.
- Keep the existing gRPC OTLP collector endpoint and explicitly configure the gRPC protocol.
- Retain Java 25, the existing SPIFFE helper identity integration, and simulated remediation.
- Merge only after the compatibility gate and a real kind deployment with demo verification pass.
  Model calls in this check are explicitly deterministic. Rehearse live-model and OpenShift-specific
  behavior on the target cluster before an event.

## Consequences

The project accepts a non-LTS Quarkus stream and the platform's Quarkus LangChain4j 2.0.0.CR1
member (LangChain4j 1.24.0); it does not import a standalone extension BOM. Dependabot continues
to own patches within the selected stream. A failed demo validation keeps the upgrade unmerged.

Reference: [official migration guide](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-4.0).
