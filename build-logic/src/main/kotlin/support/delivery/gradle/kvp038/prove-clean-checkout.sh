#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'KVP-038 clean checkout rejected: %s\n' "$*" >&2
  exit 1
}

repository=""
expected_head=""
evidence=""
self_test=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --self-test) self_test=true; shift ;;
    --root) repository="${2:?}"; shift 2 ;;
    --head) expected_head="${2:?}"; shift 2 ;;
    --evidence) evidence="${2:?}"; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

if [[ "${self_test}" == true ]]; then
  [[ -n "${evidence}" ]] || fail "self-test evidence output is required"
  misuse_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-kvp038-negative.XXXXXX")"
  cleanup_misuse() { find "${misuse_root}" -depth -delete 2>/dev/null || true; }
  trap cleanup_misuse EXIT
  git -C "${misuse_root}" init -q
  printf 'canonical\n' >"${misuse_root}/projection.txt"
  git -C "${misuse_root}" add projection.txt
  git -C "${misuse_root}" -c user.name=Kast -c user.email=proof@invalid commit -qm initial
  printf 'untracked\n' >"${misuse_root}/untracked.fixture"
  [[ -n "$(git -C "${misuse_root}" status --porcelain=v1 --untracked-files=all)" ]] ||
    fail "untracked misuse was admitted"
  mkdir -p "$(dirname "${evidence}")"
  printf 'rejectedFixtureCount=1\n' >"${evidence}.tmp"
  mv "${evidence}.tmp" "${evidence}"
  printf 'KVP-038 named untracked-input misuse: REJECTED\n'
  exit 0
fi

[[ -d "${repository}/.git" ]] || fail "root is not a Git worktree"
[[ "${expected_head}" =~ ^[0-9a-f]{40}$ ]] || fail "head is not an exact revision"
[[ "$(git -C "${repository}" rev-parse HEAD)" == "${expected_head}" ]] ||
  fail "root head changed"
[[ -z "$(git -C "${repository}" status --porcelain=v1 --untracked-files=all)" ]] ||
  fail "current worktree is dirty"
[[ -n "${evidence}" ]] || fail "evidence output is required"

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-kvp038.XXXXXX")"
checkout="${temporary_root}/checkout"
gradle_home="${temporary_root}/gradle-home"
worktree_added=false
cleanup() {
  if [[ "${worktree_added}" == true ]]; then
    git -C "${repository}" worktree remove --force "${checkout}" >/dev/null 2>&1 || true
  fi
  find "${temporary_root}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

git -C "${repository}" worktree add --detach "${checkout}" "${expected_head}"
worktree_added=true
mkdir -p "${gradle_home}"
detached_head="$(git -C "${checkout}" rev-parse HEAD)"
[[ "${detached_head}" == "${expected_head}" ]] || fail "detached head mismatch"

current_worktree_output_count="$(
  find "${checkout}" -type d -name build -prune -print | wc -l | tr -d ' '
)"
[[ "${current_worktree_output_count}" == 0 ]] || fail "checkout reused build output"
reused_gradle_cache_count="$(find "${gradle_home}" -mindepth 1 -print | wc -l | tr -d ' ')"
[[ "${reused_gradle_cache_count}" == 0 ]] || fail "fresh Gradle home is not empty"
untracked_fixture_count="$(
  git -C "${checkout}" ls-files --others --exclude-standard | wc -l | tr -d ' '
)"
[[ "${untracked_fixture_count}" == 0 ]] || fail "detached checkout contains an untracked fixture"

gradle=(
  "${checkout}/gradlew"
  --no-daemon
  --no-build-cache
  --no-configuration-cache
  --console=plain
)
printf 'KVP-038 detached checkout: %s\n' "${checkout}"
(
  cd "${checkout}"
  GRADLE_USER_HOME="${gradle_home}" "${gradle[@]}" generateKastVfsPassiveProjection
)
git -C "${checkout}" diff --exit-code -- gradle/delivery \
  docs/kast-vfs-passive-reused-index-delivery-program.md
projection_diff_clean=true

(
  cd "${checkout}"
  GRADLE_USER_HOME="${gradle_home}" "${gradle[@]}" \
    verifyKastVfsPassiveProjection \
    verifyKastModuleGraph \
    verifyForbiddenEffects \
    verifyNoLegacyArchitecture \
    verifyVfsPassiveRead \
    ideHostedVfsSafetyAcceptance \
    generateKVP034MetricSpec
)
structural_gates_passed=true

(
  cd "${checkout}"
  GRADLE_USER_HOME="${gradle_home}" "${gradle[@]}" \
    assembleIdeHostedRelease \
    verifyIdeHostedRelease
)
release_directory="$(find "${checkout}/build/release" -mindepth 1 -maxdepth 1 -type d -print -quit)"
[[ -n "${release_directory}" ]] || fail "hosted release directory is absent"
[[ "$(find "${release_directory}" -maxdepth 1 -type f ! -name '*.sha256' | wc -l | tr -d ' ')" == 2 ]] ||
  fail "hosted release did not produce exactly two payloads"
hosted_assets_built=true

installed_version="$(kast --version)"
[[ "${installed_version}" == *"g${expected_head:0:9}"*" (IDE-hosted)" ]] ||
  fail "installed Kast does not match detached head: ${installed_version}"
endpoint="$(python3 - "${checkout}" <<'PY'
import hashlib
from pathlib import Path
import sys
root = str(Path(sys.argv[1]).resolve(strict=True))
print(Path('/tmp') / ('.k' + hashlib.sha256(root.encode()).hexdigest()[:24]) / 's.endpoint.json')
PY
)"
printf 'KVP-038 waiting for supported IntelliJ endpoint: %s\n' "${endpoint}"
deadline=$((SECONDS + ${KAST_KVP038_ENDPOINT_TIMEOUT_SECONDS:-300}))
while [[ ! -f "${endpoint}" && ${SECONDS} -lt ${deadline} ]]; do
  sleep 1
done
[[ -f "${endpoint}" ]] || fail "exact detached endpoint did not appear"

printf 'KVP-038 installed acceptance executing; close IntelliJ after the four CLI operations.\n'
KAST_KVP034_CLOSE_TIMEOUT_SECONDS=60 python3 \
  "${checkout}/acceptance/ide-hosted/prove_installed.py" \
  --root "${checkout}" \
  --head "${expected_head}" \
  --metrics "${checkout}/build/reports/ide-hosted/KVP-034-metrics.json" \
  --static-proof "${checkout}/build/reports/ide-hosted/KVP-032-static-safety.json" \
  --dynamic-proof "${checkout}/build/reports/ide-hosted/KVP-033-vfs-safety.json" \
  --report "${checkout}/build/reports/ide-hosted/KVP-034-installed.json"
python3 - "${checkout}/build/reports/ide-hosted/KVP-034-installed.json" \
  "${expected_head}" <<'PY'
import json
from pathlib import Path
import sys
document = json.loads(Path(sys.argv[1]).read_text())
assert document["taskId"] == "KVP-034"
assert document["outcome"] == "COMPLETE"
assert document["repositoryHead"] == sys.argv[2]
assert [row["operation"] for row in document["operations"]] == [
    "workspace.inspect", "symbol.discover", "symbol.resolve", "symbol.describe",
]
PY
installed_acceptance_passed=true

[[ -z "$(git -C "${checkout}" diff --name-only)" ]] || fail "tracked checkout changed"
untracked_fixture_count="$(
  git -C "${checkout}" ls-files --others --exclude-standard | wc -l | tr -d ' '
)"
[[ "${untracked_fixture_count}" == 0 ]] || fail "legal path depended on untracked input"
[[ -z "$(git -C "${repository}" status --porcelain=v1 --untracked-files=all)" ]] ||
  fail "current worktree changed during detached proof"

temporary_evidence="${evidence}.tmp"
mkdir -p "$(dirname "${evidence}")"
{
  printf 'schemaVersion=1\n'
  printf 'taskId=KVP-038\n'
  printf 'outcome=COMPLETE\n'
  printf 'repositoryHead=%s\n' "${expected_head}"
  printf 'detachedHead=%s\n' "${detached_head}"
  printf 'projectionDiffClean=%s\n' "${projection_diff_clean}"
  printf 'structuralGatesPassed=%s\n' "${structural_gates_passed}"
  printf 'hostedAssetsBuilt=%s\n' "${hosted_assets_built}"
  printf 'installedAcceptancePassed=%s\n' "${installed_acceptance_passed}"
  printf 'currentWorktreeOutputCount=%s\n' "${current_worktree_output_count}"
  printf 'reusedGradleCacheCount=%s\n' "${reused_gradle_cache_count}"
  printf 'untrackedFixtureCount=%s\n' "${untracked_fixture_count}"
} >"${temporary_evidence}"
mv "${temporary_evidence}" "${evidence}"
printf 'KVP-038 detached clean checkout: COMPLETE\n'
