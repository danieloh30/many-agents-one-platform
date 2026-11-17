#!/usr/bin/env bash
# Build the "Dependency compatibility report" body from the Markdown fragments in $MAINT_OUT_DIR
# and create or update ONE open issue labelled dependency-report (found by label, edited in place).
#
# Env: GH_TOKEN (GITHUB_TOKEN with issues: write), GITHUB_REPOSITORY, REPORT_DRY_RUN=true to only print,
#      REPORT_EXTRA_MD (optional extra Markdown file appended, e.g. PR actions taken).
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

LABEL="dependency-report"
TITLE="Dependency compatibility report"
body="$MAINT_OUT_DIR/report-body.md"

{
  echo "<!-- maintained by .github/workflows/upgrade-compat.yml; edited in place, do not close -->"
  echo "_Updated: $(date -u '+%Y-%m-%d %H:%M UTC')${GITHUB_RUN_ID:+ — [workflow run](${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID})}_"
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

if [[ "${REPORT_DRY_RUN:-false}" == "true" ]]; then
  log "REPORT_DRY_RUN: issue body follows (not posted)"
  cat "$body"
  exit 0
fi

need gh
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"
gh label create "$LABEL" --repo "$GITHUB_REPOSITORY" --color 0E8A16 \
  --description "Standing dependency compatibility report" --force >/dev/null

number="$(gh issue list --repo "$GITHUB_REPOSITORY" --label "$LABEL" --state open \
  --json number --jq 'sort_by(.number) | .[0].number // empty' --limit 50)"
if [[ -n "$number" ]]; then
  gh issue edit "$number" --repo "$GITHUB_REPOSITORY" --title "$TITLE" --body-file "$body" >/dev/null
  log "updated issue #$number"
else
  gh issue create --repo "$GITHUB_REPOSITORY" --title "$TITLE" --label "$LABEL" --body-file "$body"
fi
