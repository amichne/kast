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
fixture_root="${tmp_root}/workspace"
prepared_parent="${tmp_root}/prepared"
local_prefix="${tmp_root}/local-development"
runtime_started=false
installed_kast=""

cleanup() {
  local status=$?
  if [[ "$runtime_started" == true && -x "$installed_kast" ]]; then
    "$installed_kast" --output json developer runtime stop \
      --workspace-root "$fixture_root" \
      --backend headless >/dev/null 2>&1 || true
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

mkdir -p "$evidence_dir" "$fixture_root" "$prepared_parent"
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

installed_kast="${local_prefix}/bin/kast-dev"
[[ -x "$installed_kast" ]] || die 'Prepared generation activation did not install kast-dev'
hash_kotlin_tree "$fixture_root" >"${evidence_dir}/kotlin-before.sha256"

runtime_started=true
if ! "$installed_kast" --output json developer runtime up \
  --workspace-root "$fixture_root" \
  --backend headless >"${evidence_dir}/runtime-up.json"; then
  cat "${evidence_dir}/runtime-up.json" >&2
  die 'Installed headless runtime failed to start for the representative fixture'
fi

"$installed_kast" --output json agent verify \
  --workspace-root "$fixture_root" \
  --backend headless \
  --explain >"${evidence_dir}/verify.json"
jq -e \
  '.ok == true and
   .result.semanticWorkspace.backendName == "headless" and
   .result.semanticWorkspace.workspaceKind == "DISPOSABLE_CHECKOUT" and
   .result.semanticWorkspace.evidenceQuality == "COMPILER_BACKED" and
   (.result.semanticWorkspace.sourceModuleNames | length) > 0 and
   .result.semanticWorkspace.limitations == [] and
   (.result.steps[] | select(.name == "runtime-status").result.state) == "READY"' \
  "${evidence_dir}/verify.json" >/dev/null \
  || die 'Representative fixture did not reach compiler-backed READY state'

type_symbol='fixture.domain.RenderToken'
"$installed_kast" --output json agent symbol \
  --query "$type_symbol" \
  --workspace-root "$fixture_root" \
  --backend headless >"${evidence_dir}/symbol.json"
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

"$installed_kast" --output json agent references \
  --selector-handle "$selector_handle" \
  --workspace-root "$fixture_root" \
  --backend headless \
  --limit 100 \
  --explain >"${evidence_dir}/references.json"
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
"$installed_kast" --output json agent diagnostics \
  "${diagnostic_args[@]}" \
  --workspace-root "$fixture_root" \
  --backend headless \
  --explain >"${evidence_dir}/diagnostics-clean.json"
jq -e \
  --argjson expected "${#clean_files[@]}" \
  '.ok == true and
   (.result.filePaths | length) == $expected and
   (.result.steps[] | select(.name == "diagnostics").result.semanticOutcome) == "COMPLETE" and
   (.result.steps[] | select(.name == "diagnostics").result.analyzedFileCount) == $expected and
   (.result.steps[] | select(.name == "diagnostics").result.skippedFileCount) == 0 and
   (.result.steps[] | select(.name == "diagnostics").result.cardinality) == {"type": "EXACT", "totalCount": 0} and
   (.result.steps[] | select(.name == "diagnostics").result.diagnostics) == []' \
  "${evidence_dir}/diagnostics-clean.json" >/dev/null \
  || die 'Representative main, test, and test-fixture diagnostics were not exactly clean'

broken_file="${fixture_root}/consumer/src/main/kotlin/fixture/consumer/BrokenReference.kt"
cp "${fixture_root}/broken/BrokenReference.kt" "$broken_file"
"$installed_kast" --output json agent diagnostics \
  --file-path "$broken_file" \
  --workspace-root "$fixture_root" \
  --backend headless \
  --explain >"${evidence_dir}/diagnostics-broken.json"
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

"$installed_kast" --output json agent rename \
  --selector-handle "$selector_handle" \
  --new-name RenderTokenProof \
  --workspace-root "$fixture_root" \
  --backend headless \
  --explain >"${evidence_dir}/rename-plan.json"
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

hash_kotlin_tree "$fixture_root" >"${evidence_dir}/kotlin-after.sha256"
cmp "${evidence_dir}/kotlin-before.sha256" "${evidence_dir}/kotlin-after.sha256" \
  || die 'Representative semantic proof changed fixture Kotlin bytes'

"$installed_kast" --output json developer runtime stop \
  --workspace-root "$fixture_root" \
  --backend headless >"${evidence_dir}/runtime-stop.json"
runtime_started=false
jq -e '.stopped == true and .stoppedCount == 1' "${evidence_dir}/runtime-stop.json" >/dev/null \
  || die 'Representative fixture did not stop exactly one runtime'

"$installed_kast" --output json developer local remove \
  --prefix "$local_prefix" \
  --workspace-root "$fixture_root" >"${evidence_dir}/remove.json"
[[ ! -e "$local_prefix" ]] || die 'Receipt-owned removal left the local prefix behind'
[[ ! -e "${fixture_root}/AGENTS.local.md" ]] \
  || die 'Receipt-owned removal left fixture guidance behind'

printf '%s\n' 'Representative installed semantic fixture passed'
