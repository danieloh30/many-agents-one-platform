# 0002 — Java 25 on the Quarkus LTS platform

**Status:** Accepted

## Context
Java 25 is the required baseline. The demo will be reused for a long time, so stability matters
more than the newest features. At design time Quarkus 3.40 is both the current stream and an LTS;
4.0 is a release candidate. Quarkus LangChain4j is published both standalone (newer) and as a
platform member BOM (verified against the platform).

## Decision
- JDK 25, `maven.compiler.release=25`, JVM mode, no preview features, no native image.
- `io.quarkus.platform:quarkus-bom` on the current LTS stream; follow LTS streams only.
- Quarkus LangChain4j from `io.quarkus.platform:quarkus-langchain4j-bom` at the same platform version.
- One Maven property for BOM, member BOM, and plugin.
- Use plain AI services (`@RegisterAiService`, `@Tool`, structured outputs). The agentic,
  A2A, and MCP modules are not used: they are beta/experimental upstream.

## Consequences
- LangChain4j trails the latest release; accepted.
- Virtual threads, records, sealed types, and pattern matching are available; structured
  concurrency is not used while it remains a preview API.
- Moving to Quarkus 4 is a planned major migration handled by the upgrade workflow as a reviewable PR.
