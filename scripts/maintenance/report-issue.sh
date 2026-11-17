#!/usr/bin/env bash
# Build the "Dependency compatibility report" body from the Markdown fragments in $MAINT_OUT_DIR
# and keep ONE issue labelled dependency-report (found by label, edited in place) in step with it:
#   - something needs action -> update the open issue, else reopen the latest closed one, else create one;
#   - nothing needs action   -> update the open issue and close it ("all clear"); never create one.
# "Needs action" is read from the checks' JSON results. A missing result (a check that did not run or
# failed) counts as needing action, so a broken check can never close the report.
#
# Env: GH_TOKEN (GITHUB_TOKEN with issues: write), GITHUB_REPOSITORY, REPORT_DRY_RUN=true to only print,
#      REPORT_EXTRA_MD (optional extra Markdown file appended, e.g. PR actions taken).
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

LABEL="dependency-report"

# Pending items, one per line, from the JSON results in $MAINT_OUT_DIR.
pending_items() {
  local d="$MAINT_OUT_DIR" f
  for f in quarkus-platform extension-compat charts maven-wrapper node-toolchain; do
    [[ -f "$d/$f.json" ]] || echo "$f: the check produced no result"
  done
  [[ -f "$d/quarkus-platform.json" ]] && jq -r '
    (if .patch_available == true then "Quarkus patch \(.latest_in_current_stream) in stream \(.current_stream)" else empty end),
    (if .newer_lts_available == true then "Quarkus LTS stream \(.newest_lts_stream) (\(.newest_lts_version))" else empty end)' "$d/quarkus-platform.json"
  [[ -f "$d/extension-compat.json" ]] && jq -r '.independent_libraries[]? | select(.status != "up to date")
    | "\(.coordinates) \(.pinned) -> \(.latest_stable): \(.status)"' "$d/extension-compat.json"
  [[ -f "$d/charts.json" ]] && jq -r 'if .updates_available == true then "Helm chart pins: \(.bump_level) update available" else empty end' "$d/charts.json"
  [[ -f "$d/maven-wrapper.json" ]] && jq -r 'if .update_available == true then "Maven Wrapper \(.current) -> \(.latest_in_line)" else empty end' "$d/maven-wrapper.json"
  [[ -f "$d/node-toolchain.json" ]] && jq -r '
    (if .update_available == true then "Node.js LTS \(.latest_lts) (web/.nvmrc is \(.nvmrc))" else empty end),
    (.drift[]? | "Node.js toolchain drift: \(.)")' "$d/node-toolchain.json"
  return 0
}
pending="$(pending_items)"
TITLE="Dependency compatibility report"
body="$MAINT_OUT_DIR/report-body.md"

{
  echo "<!-- maintained by .github/workflows/upgrade-compat.yml; edited in place, closed and reopened by the workflow -->"
  echo "_Updated: $(date -u '+%Y-%m-%d %H:%M UTC')${GITHUB_RUN_ID:+ — [workflow run](${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID})}_"
  echo
  if [[ -n "$pending" ]]; then
    echo "**Needs action:**"
    echo
    while IFS= read -r item; do [[ -n "$item" ]] && printf -- '- %s\n' "$item"; done <<<"$pending"
  else
    echo "**All clear:** everything is up to date. This issue closes itself and reopens when something needs action."
  fi
  echo
  for f in quarkus-platform extension-compat charts maven-wrapper node-toolchain actions; do
    if [[ -f "$MAINT_OUT_DIR/$f.md" ]]; then
      cat "$MAINT_OUT_DIR/$f.md"
    fi
  done
  if [[ -n "${REPORT_EXTRA_MD:-}" && -f "${REPORT_EXTRA_MD}" ]]; then
    cat "$REPORT_EXTRA_MD"
  fi
  echo "---"
  echo "Ownership: Dependabot owns Maven/npm/Actions/image updates (including Quarkus patches); this workflow owns Quarkus stream changes, Helm chart pins, the Maven Wrapper, and toolchain files. See \`.github/upgrade-ownership.yml\`."
  echo
  echo "An auto-merged upgrade is proven in CI, not on the demo cluster: run the pre-demo checks in docs/dependency-maintenance.md §7 before an event."
} >"$body"

if [[ -n "$pending" ]]; then decision=open; else decision=close; fi

if [[ "${REPORT_DRY_RUN:-false}" == "true" ]]; then
  log "REPORT_DRY_RUN: issue body follows (not posted)"
  echo "decision: $decision"
  cat "$body"
  exit 0
fi

need gh
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"
gh label create "$LABEL" --repo "$GITHUB_REPOSITORY" --color 0E8A16 \
  --description "Standing dependency compatibility report" --force >/dev/null

number="$(gh issue list --repo "$GITHUB_REPOSITORY" --label "$LABEL" --state open \
  --json number --jq 'sort_by(.number) | .[0].number // empty' --limit 50)"
if [[ "$decision" == "open" ]]; then
  if [[ -z "$number" ]]; then
    number="$(gh issue list --repo "$GITHUB_REPOSITORY" --label "$LABEL" --state closed \
      --json number --jq 'sort_by(.number) | last | .number // empty' --limit 50)"
    if [[ -n "$number" ]]; then
      gh issue reopen "$number" --repo "$GITHUB_REPOSITORY" --comment "Reopened: something needs action again (see the report)." >/dev/null
      log "reopened issue #$number"
    fi
  fi
  if [[ -n "$number" ]]; then
    gh issue edit "$number" --repo "$GITHUB_REPOSITORY" --title "$TITLE" --body-file "$body" >/dev/null
    log "updated issue #$number"
  else
    gh issue create --repo "$GITHUB_REPOSITORY" --title "$TITLE" --label "$LABEL" --body-file "$body"
  fi
else
  if [[ -n "$number" ]]; then
    gh issue edit "$number" --repo "$GITHUB_REPOSITORY" --title "$TITLE" --body-file "$body" >/dev/null
    gh issue close "$number" --repo "$GITHUB_REPOSITORY" --reason completed \
      --comment "All clear: every check reports up to date. Closing; the workflow reopens this issue when something needs action." >/dev/null
    log "all clear: closed issue #$number"
  else
    log "all clear: no open report issue, nothing to do"
  fi
fi
