#!/usr/bin/env bash
# Apply a Quarkus platform stream upgrade with the Quarkus update tooling, then build and test.
#
# Usage: quarkus-stream-upgrade.sh <target-version> <target-stream>
#   e.g. quarkus-stream-upgrade.sh 3.47.0 3.47
#
# Runs, from the repository root:
#   ./mvnw -B -ntp io.quarkus.platform:quarkus-maven-plugin:<target-version>:update \
#          -Dstream=<target-stream> -Drewrite=true
#   ./mvnw -B -ntp verify
# Mojo properties verified against UpdateMojo.java in quarkusio/quarkus tag 3.40.1:
#   stream, rewrite, rewriteDryRun, platformVersion (goal "update", requiresProject=true).
# No -N: in this multi-module layout the parent POM declares no Quarkus extensions, and a
# non-recursive run reports "No Quarkus extensions were found" and changes nothing (observed
# with plugin 3.40.1.2). Run recursively, the first module with extensions applies the recipe,
# which rewrites quarkus.platform.version in the parent; later modules then see no change.
#
# The script exits non-zero only when the update tooling itself fails. A failing build/test after
# a successful update is a normal outcome: it is recorded (verify_status=failure) so the PR can be
# opened with diagnostics and fail the gate visibly.
#
# Output: $MAINT_OUT_DIR/stream-upgrade.md, update.log, verify.log; step outputs
#   update_status, verify_status, platform_version_after
# Set SKIP_VERIFY=true to skip the build (e.g. local dry runs).
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

target_version="${1:?target platform version required}"
target_stream="${2:?target stream required}"
[[ "$target_version" =~ ^[0-9]+\.[0-9]+(\.[0-9A-Za-z]+)*$ ]] || die "unexpected version: $target_version"
[[ "$target_stream" =~ ^[0-9]+\.[0-9]+$ ]] || die "unexpected stream: $target_stream"

cd "$REPO_ROOT"
[[ -x ./mvnw ]] || die "Maven Wrapper ./mvnw not found in $REPO_ROOT"

before="$(pom_property quarkus.platform.version)"
update_log="$MAINT_OUT_DIR/update.log"
verify_log="$MAINT_OUT_DIR/verify.log"
report="$MAINT_OUT_DIR/stream-upgrade.md"
guide="https://github.com/quarkusio/quarkus/wiki/Migration-Guide-${target_stream}"

update_status=success
if ! ./mvnw -B -ntp "io.quarkus.platform:quarkus-maven-plugin:${target_version}:update" \
      "-Dstream=${target_stream}" -Drewrite=true >"$update_log" 2>&1; then
  update_status=failure
fi
after="$(pom_property quarkus.platform.version)"

verify_status=skipped
if [[ "$update_status" == "success" && "${SKIP_VERIFY:-false}" != "true" ]]; then
  if ./mvnw -B -ntp verify >"$verify_log" 2>&1; then
    verify_status=success
  else
    verify_status=failure
  fi
fi

summarize_log() { # log-file
  local f="$1"
  [[ -f "$f" ]] || return 0
  local first_module compiler tests reactor
  first_module="$(grep -m1 -E '^\[ERROR\] Failed to execute goal .* on project ' "$f" \
    | sed -E 's/.* on project ([^:]+):.*/\1/' || true)"
  compiler="$(grep -E '^\[ERROR\] .*\.(java|kt):\[[0-9]+,[0-9]+\]' "$f" | sed "s#$REPO_ROOT/##" | head -n 20 || true)"
  tests="$(grep -E '^\[ERROR\]   [A-Za-z0-9_.$]+[.:][A-Za-z0-9_]+|Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$f" | head -n 20 || true)"
  reactor="$(grep -E '^\[INFO\] .* (SUCCESS|FAILURE|SKIPPED)( \[|$)' "$f" | head -n 30 || true)"
  echo "- First failing module: \`${first_module:-none detected}\`"
  if [[ -n "$compiler" ]]; then
    echo; echo "<details><summary>Compiler errors (first 20)</summary>"; echo; echo '```'; printf '%s\n' "$compiler"; echo '```'; echo "</details>"
  fi
  if [[ -n "$tests" ]]; then
    echo; echo "<details><summary>Test failures (first 20)</summary>"; echo; echo '```'; printf '%s\n' "$tests"; echo '```'; echo "</details>"
  fi
  if [[ -n "$reactor" ]]; then
    echo; echo "<details><summary>Reactor summary</summary>"; echo; echo '```'; printf '%s\n' "$reactor"; echo '```'; echo "</details>"
  fi
}

{
  echo "### Quarkus stream upgrade: ${before:-?} → ${target_stream} (target ${target_version})"
  echo
  echo "| Step | Result |"
  echo "| --- | --- |"
  echo "| \`quarkus-maven-plugin:${target_version}:update -Dstream=${target_stream} -Drewrite=true\` | ${update_status} |"
  echo "| \`quarkus.platform.version\` after update | \`${after:-?}\` |"
  echo "| \`./mvnw -B -ntp verify\` | ${verify_status} |"
  echo
  echo "Migration guide: ${guide}"
  echo
  if [[ -n "$after" && "$after" != "$target_version" && "$update_status" == "success" ]]; then
    echo "> The update tool left \`quarkus.platform.version\` at \`$after\`, not \`$target_version\`. Check the update log."
    echo
  fi
  if [[ "$update_status" == "failure" ]]; then
    echo "#### Update tool failure"; echo; echo '```'; tail -n 40 "$update_log"; echo '```'
  fi
  if [[ "$verify_status" == "failure" ]]; then
    echo "#### Build/test diagnostics"; echo
    summarize_log "$verify_log"
    echo
    echo "The Quarkus update tooling automates only part of a migration; finish the remaining steps from the migration guide on this branch."
  fi
} >"$report"

gh_output update_status "$update_status"
gh_output verify_status "$verify_status"
gh_output platform_version_after "$after"
gh_summary_file "$report"
cat "$report"

[[ "$update_status" == "success" ]]
