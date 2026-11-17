#!/usr/bin/env bash
# Offline tests for node-toolchain-check.sh: a fake nodejs.org index and a throwaway repository tree.
set -o errexit -o nounset -o pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fails=0

index() { # newest-lts-major
  cat >"$work/index.json" <<EOF
[
  {"version":"v$(( $1 + 1 )).1.0","lts":false},
  {"version":"v$1.5.2","lts":"Next"},
  {"version":"v$1.5.0","lts":"Next"},
  {"version":"v$1.4.0","lts":false},
  {"version":"v24.22.3","lts":"Krypton"},
  {"version":"v24.15.0","lts":"Krypton"},
  {"version":"v22.23.5","lts":"Jod"}
]
EOF
}

tree() { # nvmrc engines lock-engines [hard-coded-node-version]
  rm -rf "$work/repo"; mkdir -p "$work/repo/web" "$work/repo/.github/workflows" "$work/repo/docs/tutorials"
  printf '%s\n' "$1" >"$work/repo/web/.nvmrc"
  jq -n --arg e "$2" '{name:"web", engines:{node:$e}}' >"$work/repo/web/package.json"
  jq -n --arg e "$3" '{name:"web", lockfileVersion:3, packages:{"":{name:"web", engines:{node:$e}}}}' >"$work/repo/web/package-lock.json"
  printf 'steps:\n  - uses: actions/setup-node@x\n    with:\n      %s\n' "${4:-node-version-file: web/.nvmrc}" >"$work/repo/.github/workflows/ci.yml"
  echo "- Node.js 24 LTS or newer" >"$work/repo/README.md"
  echo "- Node.js 24 LTS or newer." >"$work/repo/docs/tutorials/index.md"
}

run() { REPO_ROOT="$work/repo" MAINT_OUT_DIR="$work/out" NODE_DIST_INDEX="$work/index.json" "$here/node-toolchain-check.sh" "$@" 2>/dev/null; }

check() { # description actual expected
  if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: got '$2', expected '$3'"; fails=$((fails + 1)); fi
}

# 1. Up to date: newest LTS is the current major.
index 24; tree 24 '>=24.15' '>=24.15'
out="$(run)"
check "up to date: no update" "$(jq -r .update_available <<<"$out")" false
check "up to date: no drift" "$(jq -r '.drift | length' <<<"$out")" 0

# 2. Newer LTS major: reported, and --apply moves every pin to the first LTS release of that major.
index 26; tree 24 '>=24.15' '>=24.15'
out="$(run)"
check "newer LTS: update available" "$(jq -r .update_available <<<"$out")" true
check "newer LTS: floor is the first LTS release" "$(jq -r .target_floor <<<"$out")" 26.5
run --apply >/dev/null
check "apply: .nvmrc" "$(cat "$work/repo/web/.nvmrc")" 26
check "apply: package.json engines" "$(jq -r .engines.node "$work/repo/web/package.json")" '>=26.5'
check "apply: lock engines" "$(jq -r '.packages[""].engines.node' "$work/repo/web/package-lock.json")" '>=26.5'
check "apply: README prerequisite" "$(cat "$work/repo/README.md")" "- Node.js 26 LTS or newer"
check "apply: tutorial prerequisite" "$(cat "$work/repo/docs/tutorials/index.md")" "- Node.js 26 LTS or newer."
check "apply: clean afterwards" "$(run | jq -r '.update_available, (.drift | length)' | paste -sd, -)" "false,0"

# 3. Drift: engines on another major, lock out of step, a hard-coded node-version in a workflow.
index 24; tree 24 '>=22.0' '>=24.15' 'node-version: "24"'
out="$(run)"
check "drift: three findings" "$(jq -r '.drift | length' <<<"$out")" 3
run --apply >/dev/null
check "drift apply: engines back on the .nvmrc major" "$(jq -r .engines.node "$work/repo/web/package.json")" '>=24.0'
check "drift apply: workflow left for a human" "$(run | jq -r '.drift | length')" 1

(( fails == 0 )) && echo "all node-toolchain tests passed" || { echo "$fails failure(s)"; exit 1; }
