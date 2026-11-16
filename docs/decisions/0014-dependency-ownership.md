# 0014 — Dependency maintenance with disjoint ownership

**Status:** Accepted

## Context
Updates must be discovered daily, grouped compatibly, auto-merged when safe, and never produce
duplicate pull requests. Dependabot cannot run Quarkus migrations, does not parse the Helm chart
pin file or the Maven Wrapper, and is unaware of Quarkus platform compatibility.

## Decision
- Dependabot owns everything it expresses correctly (Maven incl. Quarkus patch releases, npm,
  GitHub Actions, image tags).
- One scheduled workflow owns the rest (Quarkus LTS stream changes, chart pins, Maven Wrapper,
  toolchain consistency) and always updates fixed branches in place.
- An ownership file plus a CI check keeps the two sets disjoint.
- A single required `compat-gate`, including an end-to-end run on kind in deterministic mode,
  decides mergeability. Patch/minor merge automatically after it passes; majors need review.
- Renovate is not used.

## Consequences
- No duplicate PRs by construction.
- Auto-merge works even where branch protection is unavailable, with a ruleset as an added guard where possible.
- The workflow needs a dedicated token to trigger CI on its PRs.
