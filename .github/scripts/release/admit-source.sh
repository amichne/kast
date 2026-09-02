#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'release-source-admission: %s\n' "$*" >&2
  exit 1
}

expected_source_revision=""
repository_root=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --expected-source-revision)
      [[ $# -ge 2 ]] || fail "--expected-source-revision requires a value"
      expected_source_revision="$2"
      shift 2
      ;;
    --repository-root)
      [[ $# -ge 2 ]] || fail "--repository-root requires a value"
      repository_root="$2"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "${expected_source_revision}" =~ ^[0-9a-f]{40}$ ]] ||
  fail "expected source revision must be one full Git identity"
if [[ -z "${repository_root}" ]]; then
  repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
else
  [[ -d "${repository_root}" ]] || fail "repository root does not exist"
  repository_root="$(cd "${repository_root}" && pwd -P)"
fi

observed_root="$(git -C "${repository_root}" rev-parse --show-toplevel 2>/dev/null)" ||
  fail "repository root is not a Git worktree"
observed_root="$(cd "${observed_root}" && pwd -P)"
[[ "${observed_root}" == "${repository_root}" ]] ||
  fail "repository root is not the exact Git worktree root"

source_revision="$(git -C "${repository_root}" rev-parse --verify 'HEAD^{commit}')" ||
  fail "HEAD does not resolve to one commit"
[[ "${source_revision}" =~ ^[0-9a-f]{40}$ ]] ||
  fail "HEAD does not resolve to one full Git identity"
[[ "${source_revision}" == "${expected_source_revision}" ]] ||
  fail "HEAD ${source_revision} does not match expected ${expected_source_revision}"

git -C "${repository_root}" diff --cached --quiet --exit-code -- ||
  fail "staged changes are not admitted"
git -C "${repository_root}" diff --quiet --exit-code -- ||
  fail "unstaged changes are not admitted"
untracked="$(git -C "${repository_root}" ls-files --others --exclude-standard)" ||
  fail "untracked source observation failed"
[[ -z "${untracked}" ]] || fail "untracked changes are not admitted"

printf '%s\n' "${source_revision}"
