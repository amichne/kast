#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

repo_root="$(resolve_repo_root)"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-local-semantic-e2e.XXXXXX")"
local_prefix="${repo_root}/.kast/local-development"
kast="${local_prefix}/bin/kast-dev"
runtime_started=false

cleanup() {
  local status=$?
  if [[ "$runtime_started" == true && -x "$kast" ]]; then
    "$kast" --output json developer runtime stop \
      --workspace-root "$repo_root" \
      --backend headless >/dev/null 2>&1 || true
  fi
  if [[ "$status" -ne 0 && -d "$local_prefix/state" ]]; then
    local active_generation
    active_generation="$(basename "$(readlink "$local_prefix/current" 2>/dev/null || true)")"
    if [[ -n "$active_generation" && -d "$local_prefix/state/$active_generation" ]]; then
      find "$local_prefix/state/$active_generation" \
        -type f \
        -name '*.log' \
        -print \
        -exec tail -n 200 {} \; >&2 || true
    fi
  fi
  rm -rf "$tmp_root"
  return "$status"
}
trap cleanup EXIT

command -v jq >/dev/null 2>&1 || die 'jq is required for the semantic E2E contract'
cd -- "$repo_root"

first_refresh_log="${tmp_root}/first-refresh.log"
if ! ./gradlew refreshDevelopmentLocal --no-daemon >"$first_refresh_log" 2>&1; then
  cat "$first_refresh_log" >&2
  die 'the first local-development refresh failed'
fi
[[ -x "$kast" ]] || die 'refresh did not install the receipt-owned kast-dev entrypoint'

receipt="${local_prefix}/authority.json"
first_generation="$(jq -er '.generationId' "$receipt")"
first_source_sha="$(jq -er '.source.sourceTreeSha256' "$receipt")"
first_cli_sha="$(jq -er '.components.cli.sha256' "$receipt")"
first_backend_sha="$(jq -er '.components.backend.sha256' "$receipt")"

second_refresh_log="${tmp_root}/second-refresh.log"
if ! ./gradlew refreshDevelopmentLocal --no-daemon >"$second_refresh_log" 2>&1; then
  cat "$second_refresh_log" >&2
  die 'the idempotent local-development refresh failed'
fi
grep -Fq '"skipped": true' "$second_refresh_log" \
  || die 'the unchanged second refresh must report skipped=true'
[[ "$(jq -er '.generationId' "$receipt")" == "$first_generation" ]] \
  || die 'idempotent refresh changed the active generation'
[[ "$(jq -er '.components.cli.sha256' "$receipt")" == "$first_cli_sha" ]] \
  || die 'idempotent refresh changed the installed CLI bytes'
[[ "$(jq -er '.components.backend.sha256' "$receipt")" == "$first_backend_sha" ]] \
  || die 'idempotent refresh changed the installed backend bytes'

"$kast" --output json ready --for machine --workspace-root "$repo_root" >"${tmp_root}/authority-ready.json"
jq -e \
  --arg root "$repo_root" \
  --arg generation "$first_generation" \
  --arg source_sha "$first_source_sha" \
  '.ok == true and
   .installAuthority == "local-development" and
   .localDevelopment.workspaceRoot == $root and
   .localDevelopment.generationId == $generation and
   .localDevelopment.source.sourceTreeSha256 == $source_sha and
   .binary.configuredMatchesRunning == true and
   .configuration.valid == true and
   (.localDevelopment.components | keys | sort) == ["backend", "cli", "config", "guidance", "manifest", "skill"]' \
  "${tmp_root}/authority-ready.json" >/dev/null \
  || die 'machine readiness did not prove the complete local authority'

source_file="${repo_root}/backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessWorkspaceKind.kt"
diagnostic_files=(
  "$source_file"
  "${repo_root}/analysis-api/src/test/kotlin/io/github/amichne/kast/api/DiagnosticsResultTest.kt"
  "${repo_root}/analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/AnalysisBackendContractFixture.kt"
)
type_symbol='io.github.amichne.kast.headless.HeadlessWorkspaceKind'
reference_symbol='io.github.amichne.kast.headless.HeadlessWorkspaceKind.Companion.GRADLE_MARKERS'
before_source_sha="$(sha256_file "$source_file")"
before_repository_source_sha="$first_source_sha"

runtime_started=true
if ! "$kast" --output json developer runtime up \
  --workspace-root "$repo_root" \
  --backend headless >"${tmp_root}/runtime-up.json"; then
  cat "${tmp_root}/runtime-up.json" >&2
  die 'installed headless runtime failed to start'
fi

"$kast" --output json agent verify \
  --workspace-root "$repo_root" \
  --backend headless \
  --explain >"${tmp_root}/verify.json"
jq -e \
  '.ok == true and
   .result.semanticWorkspace.backendName == "headless" and
   (.result.semanticWorkspace.workspaceKind == "PRIMARY_CHECKOUT" or
    .result.semanticWorkspace.workspaceKind == "LINKED_WORKTREE") and
   .result.semanticWorkspace.evidenceQuality == "COMPILER_BACKED" and
   (.result.steps[] | select(.name == "runtime-status").result.state) == "READY"' \
  "${tmp_root}/verify.json" >/dev/null \
  || die 'installed headless verification did not prove compiler-backed READY state'

"$kast" --output json agent symbol \
  --query "$type_symbol" \
  --workspace-root "$repo_root" \
  --backend headless \
  --explain >"${tmp_root}/symbol.json"
jq -e \
  --arg symbol "$type_symbol" \
  '.ok == true and
   .result.outcome.type == "RESOLVED" and
   .result.outcome.source == "compiler" and
   .result.outcome.symbol.fqName == $symbol' \
  "${tmp_root}/symbol.json" >/dev/null \
  || die 'installed exact lookup did not resolve from the compiler'

"$kast" --output json agent symbol \
  --query "$reference_symbol" \
  --workspace-root "$repo_root" \
  --backend headless \
  --explain >"${tmp_root}/reference-symbol.json"
jq -e \
  --arg symbol "$reference_symbol" \
  '.ok == true and
   .result.outcome.type == "RESOLVED" and
   .result.outcome.source == "compiler" and
   .result.outcome.symbol.fqName == $symbol and
   .result.outcome.symbol.kind == "PROPERTY" and
   .result.outcome.symbol.visibility == "PRIVATE"' \
  "${tmp_root}/reference-symbol.json" >/dev/null \
  || die 'known reference anchor did not resolve as the exact private property'
reference_file="$(jq -er '.result.outcome.symbol.location.filePath' "${tmp_root}/reference-symbol.json")"
reference_offset="$(jq -er '.result.outcome.symbol.location.startOffset' "${tmp_root}/reference-symbol.json")"
reference_containing_type="$(jq -er '.result.outcome.symbol.containingDeclaration' "${tmp_root}/reference-symbol.json")"

"$kast" --output json agent references \
  --symbol "$reference_symbol" \
  --declaration-file "$reference_file" \
  --declaration-start-offset "$reference_offset" \
  --kind property \
  --containing-type "$reference_containing_type" \
  --workspace-root "$repo_root" \
  --backend headless \
  --limit 100 \
  --explain >"${tmp_root}/references.json"
jq -e \
  '.ok == true and
   .result.outcome == "AVAILABLE" and
   .result.page.cardinality.type == "EXACT" and
   .result.page.cardinality.totalCount > 0 and
   .result.page.returnedCount > 0 and
   .result.page.truncated == false and
   .result.limitations == []' \
  "${tmp_root}/references.json" >/dev/null \
  || die 'installed reference lookup was not known, nonzero, exact, and exhaustive'

diagnostic_file_args=()
for diagnostic_file in "${diagnostic_files[@]}"; do
  diagnostic_file_args+=(--file-path "$diagnostic_file")
done
"$kast" --output json agent diagnostics \
  "${diagnostic_file_args[@]}" \
  --workspace-root "$repo_root" \
  --backend headless \
  --explain >"${tmp_root}/diagnostics.json"
jq -e \
  --argjson requested_file_count "${#diagnostic_files[@]}" \
  '.ok == true and
   (.result.filePaths | length) == $requested_file_count and
   (.result.steps[] | select(.name == "diagnostics").result.semanticOutcome) == "COMPLETE" and
   (.result.steps[] | select(.name == "diagnostics").result.requestedFileCount) == $requested_file_count and
   (.result.steps[] | select(.name == "diagnostics").result.analyzedFileCount) == $requested_file_count and
   (.result.steps[] | select(.name == "diagnostics").result.skippedFileCount) == 0 and
   (.result.steps[] | select(.name == "diagnostics").result.severityCounts.total) == 0 and
   (.result.steps[] | select(.name == "diagnostics").result.cardinality) == {"type": "EXACT", "totalCount": 0} and
   (.result.steps[] | select(.name == "diagnostics").result.diagnostics) == []' \
  "${tmp_root}/diagnostics.json" >/dev/null \
  || die 'installed diagnostics did not completely analyze clean main, test, and test-fixture Kotlin files'

"$kast" --output json agent rename \
  --symbol "$type_symbol" \
  --new-name HeadlessWorkspaceKindProof \
  --workspace-root "$repo_root" \
  --backend headless \
  --explain >"${tmp_root}/rename-plan.json"
jq -e \
  '.ok == true and
   .result.type == "KAST_AGENT_RENAME_PLAN" and
   .result.applyRequired == true and
   .result.request.method == "symbol/rename" and
   (.result.preview.edits | length) > 0 and
   (.result.preview.affectedFiles | length) > 0 and
   (.result.preview.fileHashes | length) == (.result.preview.affectedFiles | length)' \
  "${tmp_root}/rename-plan.json" >/dev/null \
  || die 'installed rename did not return an explicit non-applied mutation plan'
[[ "$(sha256_file "$source_file")" == "$before_source_sha" ]] \
  || die 'plan-only rename changed source bytes'
"$kast" --output json developer local snapshot \
  --source-root "$repo_root" \
  --output-file "${tmp_root}/source-after-rename.json" >/dev/null
[[ "$(jq -er '.sourceTreeSha256' "${tmp_root}/source-after-rename.json")" == "$before_repository_source_sha" ]] \
  || die 'plan-only rename changed repository source state'

"$kast" --output json developer runtime stop \
  --workspace-root "$repo_root" \
  --backend headless >"${tmp_root}/runtime-stop.json"
runtime_started=false
jq -e '.stopped == true and .stoppedCount == 1' "${tmp_root}/runtime-stop.json" >/dev/null \
  || die 'installed headless runtime did not stop explicitly'

state_root="${local_prefix}/state/${first_generation}"
if grep -R -E --include='*.log' \
  'Kast project-open workspace setup (failed|prepared)|Homebrew CLI receipt' "$state_root"; then
  die 'headless startup invoked the unrelated IDEA project-open release-authority path'
fi
grep -R -F --include='*.log' \
  'Kast project-open workspace setup skipped: disabled' "$state_root" >/dev/null \
  || die 'headless runtime did not receive the local project-open opt-out'

"$kast" --output json ready --for machine --workspace-root "$repo_root" >"${tmp_root}/authority-after.json"
jq -e \
  --arg source_sha "$first_source_sha" \
  '.ok == true and .localDevelopment.source.sourceTreeSha256 == $source_sha' \
  "${tmp_root}/authority-after.json" >/dev/null \
  || die 'semantic execution changed or invalidated the active source authority'

if ! ./gradlew removeDevelopmentLocal \
  -PkastLocalWorkspaceRoot="$repo_root" \
  --no-daemon >"${tmp_root}/remove.log" 2>&1; then
  cat "${tmp_root}/remove.log" >&2
  die 'receipt-owned local authority removal failed'
fi
[[ ! -e "$local_prefix" ]] || die 'removal left the receipt-owned local prefix behind'
[[ ! -e "${repo_root}/AGENTS.local.md" ]] || die 'removal left the receipt-owned guidance projection behind'
[[ ! -L "${repo_root}/AGENTS.local.md" ]] || die 'removal left a dangling receipt-owned guidance projection behind'

printf '%s\n' 'Local development installed semantic E2E passed'
