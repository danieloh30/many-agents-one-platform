# CLAUDE.md

Project conventions for any AI coding assistant working in this repository. Human contributors
follow the same rules.

## What this project is

A reusable demonstration of operable Java multi-agent services on Kubernetes/OpenShift. The design
is in `docs/`; the ordered work is in `IMPLEMENTATION_HANDOFF.md`. Read that file's §1 list before
writing code.

## Non-negotiable rules

1. **Java 25 or newer.** `maven.compiler.release` is 25. Never lower it, never add a toolchain or
   CI matrix entry below 25, never use `--enable-preview`. A newer JDK is allowed only through the
   toolchain upgrade process in `docs/dependency-maintenance.md`.
2. **Pinned versions only.** Use `docs/versions.md`. Do not add or bump a dependency ad hoc. Quarkus
   LangChain4j comes from the platform member BOM; never import the standalone BOM.
3. **Do not invent APIs or configuration properties.** Verify every framework or platform name in
   official documentation or source for the pinned version before using it.
4. **Contracts are normative.** `docs/contracts.md` and `contracts/` change together with code.
5. **No real remediation, ever.** tool-service must not gain a Kubernetes or cloud client.
6. **No fabricated data.** Unknown operational values are `null` + `UNAVAILABLE`, never zero or "OK".
7. **No silent model fallback.** Deterministic mode is explicit and labelled.
8. **No chain-of-thought.** Do not request, log, store, or display model reasoning; structured
   outputs and concise explanations only. Never log prompts, completions, or secrets.
9. **Event-neutral.** `README.md` and the SPA never mention a conference, session title, or date.
10. **No live cluster changes** unless the user explicitly asks in the current conversation. Work on kind.
11. **Decisions change through ADRs** (`docs/decisions/`), not silently.

## Attribution rules

- Commits are authored and committed with the repository owner's Git identity, configured **for
  this repository only** (`git config --local user.name` / `user.email`). Do not change global Git
  configuration.
- **Never** use Claude, Anthropic, or any AI identity as author, committer, or co-author. Do not
  add `Co-Authored-By` trailers for AI tools, "Generated with …" lines, or AI attribution in commit
  messages, PR descriptions, code comments, or docs. `.claude/settings.json` disables automatic
  attribution; keep it that way.
- Before every push, inspect what will be pushed:
  ```bash
  git log origin/main..HEAD --format='%H%n author:    %an <%ae>%n committer: %cn <%ce>%n%B---'
  ```
  and confirm there is no AI name, email, or trailer.
- Never rewrite published history, never force-push `main`, never alter existing commits' authorship.
- Never print tokens, keys, or credentials. Never commit `.env*` files.
- Automation commits (Dependabot, workflows) keep their real bot identities.

## Conventions

- **Layout:** see `IMPLEMENTATION_HANDOFF.md` §3. Java package root `io.github.danieloh30.maop`.
- **Packages per service module** (`io.github.danieloh30.maop.<service>.*`): `agentic` (model-facing agents,
  deterministic scripts), `model` (domain types and rules), `resource` (REST resources, filters, problem
  responses), `service` (workflow, messaging handlers, persistence, lifecycle). Omit a package a service does not
  need. In `maop-common`, `agentic` holds the model gateway and tool loop and `model` holds the shared contract
  records.
- **Java style:** records for contract and value types; sealed interfaces for closed sets; no
  Lombok; constructor injection; blocking code on virtual threads rather than reactive chains
  unless an API requires otherwise; `java.time` only; UUIDv7 for new identifiers.
- **Persistence:** Flyway migrations only (`V<n>__<desc>.sql`), never `hibernate` schema generation
  outside tests; one schema per owning service; every state change that emits an event writes the
  outbox row in the same transaction.
- **Messaging:** all publish/consume goes through the `maop-common` JetStream adapter; every
  consumer with a database uses the inbox; acknowledge only after commit.
- **Errors:** RFC 9457 problem responses with `code` and `traceId`.
- **Configuration:** `maop.*` for project settings, standard `quarkus.*` otherwise; secrets only
  from environment/Secrets.
- **Tests:** unit tests beside code; integration tests use real PostgreSQL, NATS, and OPA
  containers — no mocks for those; model calls in tests use deterministic mode.
- **Frontend:** TypeScript `strict`; API types generated from OpenAPI; design tokens from
  `docs/ui-design.md`; no runtime CDN assets; every status has text + icon, not colour alone.
- **Manifests:** plain `image:` fields with tag and digest; restricted pod security; no `runAsUser`
  in the OpenShift overlay.
- **Docs:** update the relevant doc in the same commit as a behaviour change. Mermaid for diagrams.
- **Commits:** imperative subject ≤ 72 characters, body explains why; one backlog item per commit
  or small series; reference the backlog ID (for example `B-14`).

## Verification commands

Run the relevant ones before declaring a task done, and report real output.

```bash
java -version                                  # must report 25 or newer
./mvnw -B verify                               # build, unit, contract and integration tests (Testcontainers)
./mvnw -B -pl <module> -am verify              # one service and its dependencies
(cd web && npm ci && npm run typecheck && npm run lint && npm test && npm run build)
podman run --rm -v "$PWD/policy":/policy:Z docker.io/openpolicyagent/opa:1.22.4-static test /policy --coverage
./demo.sh render --platform openshift          # manifests render offline (no cluster access)
scripts/local/stack.sh up && scripts/local/stack.sh e2e   # cluster-free end-to-end evidence
./demo.sh verify                               # against the current cluster context — ask first
```

Attribution checks before pushing:

```bash
git log --format='%an <%ae> | %cn <%ce>' | sort -u          # only the owner's identity (and real bots)
git log --format=%B | grep -i -E 'co-authored-by|claude|anthropic|generated with' && echo "FIX" || echo "clean"
grep -rIl -i -E 'ai summit|paris|conference|2026' README.md web/src 2>/dev/null   # must print nothing (the `summit` profile name is allowed)
```

## When unsure

Prefer stopping and recording the question in `IMPLEMENTATION_HANDOFF.md` §6/§7 over guessing —
especially for anything marked "verify on cluster".
