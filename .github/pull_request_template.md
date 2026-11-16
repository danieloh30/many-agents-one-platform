## What and why

<!-- What changes, and why. Reference the backlog item (for example B-14) or ADR. -->

## How it was verified

<!-- Commands run and their real results (see CLAUDE.md "Verification commands"). -->

- [ ] `./mvnw -B verify`
- [ ] `web`: `npm ci && npm run typecheck && npm run lint && npm test && npm run build`
- [ ] `opa fmt --fail policy/ && opa check --strict policy/ && opa test policy/`
- [ ] `kubectl kustomize` renders every overlay

## Checklist

- [ ] Java stays at 25 or newer; no `--enable-preview`
- [ ] Versions come from `docs/versions.md` / the owning engine (`.github/upgrade-ownership.yml`)
- [ ] Contracts (`docs/contracts.md`, `contracts/`) updated together with code, if affected
- [ ] Docs updated in the same change as behaviour; decisions changed only through an ADR
- [ ] No secrets, tokens, `.env*` files, prompts, or completions in code, logs, or fixtures
- [ ] Anything that still needs cluster verification is listed below

## Needs cluster verification

<!-- None, or the items and the procedure. -->
