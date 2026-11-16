# 0013 — Model modes

**Status:** Accepted

## Context
The demo must use real model calls by default with configurable endpoint, model, and server-side
credentials. Tests and CI need determinism, and a stage fallback must exist without misleading anyone.

## Decision
- `maop.model.mode=live` is the default: an OpenAI-compatible endpoint configured through
  `quarkus.langchain4j.openai.*` from a Secret and ConfigMap.
- `maop.model.mode=deterministic` selects an explicit `ScriptedChatModel` shipped in the main
  artifact (so the switch needs no rebuild) that returns fixed structured outputs per purpose.
- The mode is stamped on every event, stored result, and span, and shown permanently in the console.
- There is no automatic fallback between modes.
- Tool data is always synthetic and remediation is always simulated, and both are always labelled.

## Consequences
- CI and the kind end-to-end gate need no credentials.
- Switching modes is an explicit operator action that restarts the agents.
- How the mode switch binds to `@RegisterAiService` on the pinned extension version is verified by
  an early spike (backlog B-03).
