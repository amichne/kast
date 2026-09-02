#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'test-release-source-admission: %s\n' "$*" >&2
  exit 1
}

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
admission="${repository_root}/.github/scripts/release/admit-source.sh"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-source-admission.XXXXXX")"
cleanup() {
  rm -rf -- "${fixture}"
}
trap cleanup EXIT

git -C "${fixture}" init --quiet
git -C "${fixture}" config user.name "Kast Release Test"
git -C "${fixture}" config user.email "release-test@kast.invalid"
printf 'canonical\n' >"${fixture}/tracked.txt"
git -C "${fixture}" add tracked.txt
git -C "${fixture}" commit --quiet -m baseline
revision="$(git -C "${fixture}" rev-parse HEAD)"

admitted="$(${admission} \
  --repository-root "${fixture}" \
  --expected-source-revision "${revision}")"
[[ "${admitted}" == "${revision}" ]] || fail "clean checkout returned the wrong identity"

expect_rejected() {
  local name="$1"
  local artifact="${fixture}/${name}.artifact"
  if admitted="$(${admission} \
    --repository-root "${fixture}" \
    --expected-source-revision "${revision}" 2>/dev/null)"; then
    : >"${artifact}"
    fail "${name} source was admitted as ${admitted}"
  fi
  [[ ! -e "${artifact}" ]] || fail "${name} wrote an artifact before rejection"
}

if [[ "${revision:0:1}" == "a" ]]; then
  wrong_revision="b${revision:1}"
else
  wrong_revision="a${revision:1}"
fi
if "${admission}" \
  --repository-root "${fixture}" \
  --expected-source-revision "${wrong_revision}" >/dev/null 2>&1; then
  fail "unexpected source revision was admitted"
fi

printf 'staged\n' >>"${fixture}/tracked.txt"
git -C "${fixture}" add tracked.txt
expect_rejected staged
git -C "${fixture}" restore --staged --worktree tracked.txt

printf 'unstaged\n' >>"${fixture}/tracked.txt"
expect_rejected unstaged
git -C "${fixture}" restore tracked.txt

printf 'untracked\n' >"${fixture}/untracked.txt"
expect_rejected untracked

printf 'release-source-admission: clean identity admitted; revision, staged, unstaged, and untracked sources rejected\n'
