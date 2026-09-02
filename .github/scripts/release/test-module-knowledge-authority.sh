#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'module-knowledge-authority: %s\n' "$*" >&2
  exit 1
}

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
source_revision="$(git -C "${repository_root}" rev-parse --verify 'HEAD^{commit}')"
[[ "${source_revision}" =~ ^[0-9a-f]{40}$ ]] || fail "HEAD is not one full Git identity"
knowledge="${repository_root}/build/reports/kast-architecture/kast-module-knowledge.json"
ignored_directory="${repository_root}/.gradle/kast-module-knowledge-authority-test"
ignored_guide="${ignored_directory}/AGENTS.md"
[[ ! -e "${ignored_directory}" ]] || fail "ignored fixture path already exists"

cleanup() {
  rm -f -- "${ignored_guide}"
  rmdir "${ignored_directory}" 2>/dev/null || true
}
trap cleanup EXIT

digest() {
  python3 -c 'import hashlib, pathlib, sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' "$1"
}

gradle_command=(
  "${repository_root}/gradlew"
  -Dorg.gradle.jvmargs=-Xmx5g
  -Pversion=0.0.0
  -PkastSourceRevision="${source_revision}"
  --rerun-tasks
  generateKastModuleKnowledge
  --console=plain
)

(cd "${repository_root}" && "${gradle_command[@]}")
[[ -f "${knowledge}" ]] || fail "baseline module knowledge was not generated"
baseline_digest="$(digest "${knowledge}")"

mkdir "${ignored_directory}"
printf '%s\n' '# Ignored attacker-controlled guide' >"${ignored_guide}"
git -C "${repository_root}" check-ignore -q -- "${ignored_guide}" ||
  fail "attack fixture is not Git-ignored"
(cd "${repository_root}" && "${gradle_command[@]}")

[[ "$(digest "${knowledge}")" == "${baseline_digest}" ]] ||
  fail "ignored guide changed canonical module knowledge"
if grep -Fq 'Ignored attacker-controlled guide' "${knowledge}"; then
  fail "ignored guide contents entered canonical module knowledge"
fi

printf '%s\n' 'module-knowledge-authority: real generation is stable against ignored guides'
