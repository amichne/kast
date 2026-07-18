#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

hash_kotlin_tree() {
  local root="$1"
  python3 - "$root" <<'PY'
import hashlib
import sys
from pathlib import Path

root = Path(sys.argv[1])
for path in sorted(root.rglob("*.kt")):
    print(f"{path.relative_to(root).as_posix()} {hashlib.sha256(path.read_bytes()).hexdigest()}")
PY
}

repo_root="$(resolve_repo_root)"
archive="${KAST_PREPARED_GENERATION_ARCHIVE:-}"
evidence_dir="${KAST_SEMANTIC_FIXTURE_EVIDENCE_DIR:-${repo_root}/build/installed-semantic-fixture-evidence}"
fixture_source="${repo_root}/backend-headless/src/test/resources/fixtures/installed-semantic-gradle"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-installed-semantic-fixture.XXXXXX")"
tmp_root="$(cd -- "$tmp_root" && pwd -P)"
fixture_root="${tmp_root}/workspace"
fixture_home="${tmp_root}/home"
prepared_parent="${tmp_root}/prepared"
local_prefix="${tmp_root}/local-development"
lease_acquired=false
lease_id=""
installed_kast=""

invoke_installed_kast() {
  env -u CODEX_HOME HOME="$fixture_home" "$installed_kast" "$@"
}

run_installed_kast() {
  local evidence_name="$1"
  shift
  local lease_args=()
  if [[ -n "$lease_id" ]]; then
    lease_args+=(--lease-id "$lease_id")
  fi
  invoke_installed_kast --output json "$@" \
    --workspace-root "$fixture_root" \
    --backend headless \
    "${lease_args[@]}" >"${evidence_dir}/${evidence_name}.json"
}

cleanup() {
  local status=$?
  if [[ "$lease_acquired" == true && -x "$installed_kast" ]]; then
    invoke_installed_kast --output json agent lease release \
      --workspace-root "$fixture_root" \
      --backend headless \
      --lease-id "$lease_id" >/dev/null 2>&1 || true
  fi
  if [[ "$status" -ne 0 && -d "$local_prefix" ]]; then
    mkdir -p "${evidence_dir}/runtime-logs"
    while IFS= read -r log_file; do
      cp "$log_file" "${evidence_dir}/runtime-logs/$(basename -- "$log_file")" || true
    done < <(find "$local_prefix" -type f -name '*.log' -print)
  fi
  find "$tmp_root" -depth -delete
  return "$status"
}
trap cleanup EXIT

[[ -n "$archive" ]] || die 'KAST_PREPARED_GENERATION_ARCHIVE is required'
[[ -f "$archive" ]] || die "Prepared generation archive not found: $archive"
[[ -d "$fixture_source" ]] || die "Semantic fixture not found: $fixture_source"
command -v jq >/dev/null 2>&1 || die 'jq is required for the semantic fixture'
command -v python3 >/dev/null 2>&1 || die 'python3 is required for the semantic fixture'
command -v tar >/dev/null 2>&1 || die 'tar is required for the semantic fixture'
command -v git >/dev/null 2>&1 || die 'git is required for the semantic fixture'

mkdir -p "$evidence_dir" "$fixture_root" "$fixture_home" "$prepared_parent"
cp -R "${fixture_source}/." "$fixture_root/"
cp "${repo_root}/gradlew" "$fixture_root/gradlew"
cp "${repo_root}/gradlew.bat" "$fixture_root/gradlew.bat"
mkdir -p "$fixture_root/gradle"
cp -R "${repo_root}/gradle/wrapper" "$fixture_root/gradle/wrapper"
chmod +x "$fixture_root/gradlew"
git init --quiet "$fixture_root"
git -C "$fixture_root" check-ignore --quiet -- AGENTS.local.md \
  || die 'Representative fixture must source-own its local-guidance ignore rule'

tar --zstd --extract --no-same-owner --file "$archive" --directory "$prepared_parent"
prepared_generation=""
prepared_candidate_count=0
while IFS= read -r candidate; do
  prepared_generation="$candidate"
  prepared_candidate_count=$((prepared_candidate_count + 1))
done < <(find "$prepared_parent" -mindepth 1 -maxdepth 1 -type d -print)
[[ "$prepared_candidate_count" -eq 1 ]] \
  || die "Prepared archive must contain exactly one generation directory"
prepared_kast="${prepared_generation}/bin/kast"
[[ -x "$prepared_kast" ]] || die 'Prepared generation does not contain executable bin/kast'

"$prepared_kast" --output json developer local verify \
  --source-root "$repo_root" \
  --prepared-generation "$prepared_generation" \
  >"${evidence_dir}/prepared-verify.json"

"$prepared_kast" --output json developer local activate \
  --source-root "$repo_root" \
  --workspace-root "$fixture_root" \
  --prefix "$local_prefix" \
  --prepared-generation "$prepared_generation" \
  >"${evidence_dir}/activation.json"

installed_kast="${local_prefix}/bin/kast"
[[ -x "$installed_kast" ]] || die 'Prepared generation activation did not install kast'
active_generation="$(jq -er '.receipt.generationId' "${evidence_dir}/activation.json")"
hash_kotlin_tree "$fixture_root" >"${evidence_dir}/kotlin-before.sha256"

if ! run_installed_kast lease-acquire agent lease acquire; then
  cat "${evidence_dir}/lease-acquire.json" >&2
  die 'Installed headless lease failed to acquire for the representative fixture'
fi
lease_id="$(jq -er '.result.leaseId' "${evidence_dir}/lease-acquire.json")"
lease_acquired=true
jq -e \
  --arg generation "$active_generation" \
  --arg workspace_root "$fixture_root" \
  '.ok == true and
   .result.state == "READY" and
   .result.workspaceRoot == $workspace_root and
   .result.backendName == "headless" and
   .result.ownership == "STARTED" and
   .result.installation.authority == "local-development" and
   .result.installation.generation == $generation and
   .result.runtime.descriptor.workspaceRoot == $workspace_root and
   .result.runtime.descriptor.backendName == "headless" and
   .result.runtime.descriptor.pid > 0 and
   .result.runtime.process.pid == .result.runtime.descriptor.pid and
   (.result.runtime.process.startedAt | length) > 0 and
   (.result.runtime.descriptorPath | length) > 0' \
  "${evidence_dir}/lease-acquire.json" >/dev/null \
  || die 'Installed headless lease did not bind the active generation and exact READY runtime'

wrong_root="${tmp_root}/wrong-workspace"
mkdir -p "$wrong_root"
if invoke_installed_kast --output json agent lease status \
  --workspace-root "$wrong_root" \
  --backend headless \
  --lease-id "$lease_id" >"${evidence_dir}/lease-wrong-root.json"; then
  die 'Installed lease accepted a different workspace root'
fi
jq -e '.error.code == "WORKSPACE_LEASE_ROOT_MISMATCH"' \
  "${evidence_dir}/lease-wrong-root.json" >/dev/null \
  || die 'Installed lease did not return the typed wrong-root failure'

run_installed_kast verify agent verify --explain
jq -e \
  '.ok == true and
   .result.semanticWorkspace.backendName == "headless" and
   .result.semanticWorkspace.workspaceKind == "DISPOSABLE_CHECKOUT" and
   .result.semanticWorkspace.evidenceQuality == "COMPILER_BACKED" and
   (.result.semanticWorkspace.sourceModuleNames | length) > 0 and
   (.result.semanticWorkspace.limitations | all(. == "REFERENCE_INDEX_UNAVAILABLE")) and
   (.result.steps[] | select(.name == "runtime-status").result.state) == "READY"' \
  "${evidence_dir}/verify.json" >/dev/null \
  || die 'Representative fixture did not reach compiler-backed READY state with only the expected cold-index limitation'

type_symbol='fixture.domain.RenderToken'
run_installed_kast symbol agent symbol --query "$type_symbol"
jq -e \
  --arg symbol "$type_symbol" \
  '.ok == true and
   .result.outcome == "RESOLVED" and
   .result.source == "compiler" and
   .result.identity.fqName == $symbol and
   (.result.selectorHandle | type) == "string" and
   (.result.selectorHandle | length) > 0' \
  "${evidence_dir}/symbol.json" >/dev/null \
  || die 'Representative fixture exact symbol did not expose a compiler-issued selector handle'
selector_handle="$(jq -er '.result.selectorHandle' "${evidence_dir}/symbol.json")"

run_installed_kast references agent references \
  --selector-handle "$selector_handle" \
  --limit 100 \
  --explain
jq -e \
  '.ok == true and
   .result.outcome == "AVAILABLE" and
   .result.page.cardinality.type == "EXACT" and
   .result.page.cardinality.totalCount > 0 and
   .result.page.returnedCount > 0 and
   .result.page.truncated == false and
   .result.limitations == []' \
  "${evidence_dir}/references.json" >/dev/null \
  || die 'Representative fixture references were not exact, exhaustive, and nonzero'

run_installed_kast rename-plan agent rename \
  --selector-handle "$selector_handle" \
  --new-name RenderTokenProof \
  --explain
jq -e \
  --arg handle "$selector_handle" \
  '.ok == true and
   .result.type == "KAST_AGENT_RENAME_PLAN" and
   .result.applyRequired == true and
   .result.request.params.selectorHandle == $handle and
   (.result.preview.edits | length) > 0 and
   (.result.preview.affectedFiles | length) > 0 and
   (.result.preview.fileHashes | length) == (.result.preview.affectedFiles | length)' \
  "${evidence_dir}/rename-plan.json" >/dev/null \
  || die 'Representative rename did not reuse the selector handle for a non-applied plan'

clean_files=(
  "${fixture_root}/domain/src/main/kotlin/fixture/domain/RenderToken.kt"
  "${fixture_root}/domain/src/test/kotlin/fixture/domain/RenderTokenTestProbe.kt"
  "${fixture_root}/domain/src/testFixtures/kotlin/fixture/domain/RenderTokenFixture.kt"
  "${fixture_root}/consumer/src/main/kotlin/fixture/consumer/RenderTokenConsumer.kt"
  "${fixture_root}/consumer/src/test/kotlin/fixture/consumer/RenderTokenConsumerTestProbe.kt"
)
diagnostic_args=()
for clean_file in "${clean_files[@]}"; do
  diagnostic_args+=(--file-path "$clean_file")
done
run_installed_kast diagnostics-clean agent diagnostics \
  "${diagnostic_args[@]}" \
  --explain
jq -e \
  --argjson expected "${#clean_files[@]}" \
  '(.result.steps[] | select(.name == "diagnostics").result) as $diagnostics |
   .ok == true and
   (.result.filePaths | length) == $expected and
   $diagnostics.semanticOutcome == "COMPLETE" and
   $diagnostics.analyzedFileCount == $expected and
   $diagnostics.skippedFileCount == 0 and
   $diagnostics.cardinality == {"type": "EXACT", "totalCount": 0} and
   $diagnostics.diagnostics == []' \
  "${evidence_dir}/diagnostics-clean.json" >/dev/null \
  || die 'Representative main, test, and test-fixture diagnostics were not exactly clean'

broken_file="${fixture_root}/consumer/src/main/kotlin/fixture/consumer/BrokenReference.kt"
cp "${fixture_root}/broken/BrokenReference.kt" "$broken_file"
run_installed_kast diagnostics-broken agent diagnostics \
  --file-path "$broken_file" \
  --explain
jq -e \
  '.ok == true and
   (.result.steps[] | select(.name == "diagnostics").result.semanticOutcome) == "COMPLETE" and
   (.result.steps[] | select(.name == "diagnostics").result.analyzedFileCount) == 1 and
   (.result.steps[] | select(.name == "diagnostics").result.cardinality.type) == "EXACT" and
   (.result.steps[] | select(.name == "diagnostics").result.cardinality.totalCount) > 0 and
   (.result.steps[] | select(.name == "diagnostics").result.severityCounts.error) > 0 and
   any((.result.steps[] | select(.name == "diagnostics").result.diagnostics)[]; .code == "UNRESOLVED_REFERENCE")' \
  "${evidence_dir}/diagnostics-broken.json" >/dev/null \
  || die 'Representative broken source did not produce an exact unresolved-reference diagnostic'
/bin/unlink "$broken_file"

hash_kotlin_tree "$fixture_root" >"${evidence_dir}/kotlin-after.sha256"
cmp "${evidence_dir}/kotlin-before.sha256" "${evidence_dir}/kotlin-after.sha256" \
  || die 'Representative semantic proof changed fixture Kotlin bytes'

run_installed_kast lease-release agent lease release
lease_acquired=false
jq -e \
  '.ok == true and
   .result.state == "RELEASED" and
   .result.releaseReceipt.runtimeStopped == true and
   .result.releaseReceipt.reason == "OWNED_RUNTIME_STOPPED"' \
  "${evidence_dir}/lease-release.json" >/dev/null \
  || die 'Representative fixture lease did not stop its exact owned runtime'

run_installed_kast lease-release-idempotent agent lease release
jq -e --slurpfile first "${evidence_dir}/lease-release.json" \
  '.ok == true and .result.releaseReceipt == $first[0].result.releaseReceipt' \
  "${evidence_dir}/lease-release-idempotent.json" >/dev/null \
  || die 'Representative fixture lease release was not idempotent'

invoke_installed_kast --output json developer local remove \
  --prefix "$local_prefix" \
  --workspace-root "$fixture_root" >"${evidence_dir}/remove.json"
[[ ! -e "$local_prefix" ]] || die 'Receipt-owned removal left the local prefix behind'
[[ ! -e "${fixture_root}/AGENTS.local.md" ]] \
  || die 'Receipt-owned removal left fixture guidance behind'

printf '%s\n' 'Representative installed semantic fixture passed'
