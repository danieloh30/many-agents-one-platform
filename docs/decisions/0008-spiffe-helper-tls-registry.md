# 0008 — SPIFFE mTLS through spiffe-helper and the Quarkus TLS registry

**Status:** Accepted

## Context
The demo needs SPIFFE/SPIRE identity on a real authenticated service connection. On the pinned
Quarkus LTS there is no SPIFFE extension (one exists only in the 4.0 pre-release line). The
`java-spiffe` library works with the Workload API but is 0.x and brings its own gRPC/Netty stack
next to the one Quarkus manages.

## Decision
- SPIRE issues X.509-SVIDs; identities are registered with one `ClusterSPIFFEID`.
- A `spiffe-helper` native sidecar writes SVID, key, and bundle as PEM files to a memory volume.
- Quarkus consumes them through a named TLS registry configuration with periodic reload.
- All calls to tool-service use mutual TLS; the server extracts the SPIFFE ID from the URI SAN;
  clients pin the expected server SPIFFE ID.
- Scope: the tool API only. Other hops are protected by NetworkPolicy and credentials and are
  documented as out of scope.

## Consequences
- Only documented Quarkus properties and no extra client library.
- Rotation depends on file reload; covered by an integration test and a short SVID TTL.
- One extra container per pod.
- Revisit when a Quarkus LTS ships a native SPIFFE client.
