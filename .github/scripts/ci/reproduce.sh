#!/usr/bin/env bash
# Build a committed revision with CI's gate, retaining the checkout and evidence.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
if [[ $# -ne 1 ]]; then
  echo 'usage: bash .github/scripts/ci/reproduce.sh <commit-or-ref>' >&2
  exit 2
fi
sha="$(git -C "$root" rev-parse --verify "${1}^{commit}")"
parent="$(mktemp -d "${TMPDIR:-/tmp}/kast-ci-reproduce.XXXXXX")"
checkout="$parent/checkout"
git -C "$root" worktree add --detach "$checkout" "$sha"
printf 'CI checkout and evidence retained at %s\n' "$checkout"
# Pin the IDEA distribution from the repository, as on hosted CI.
unset KAST_ACCEPTANCE_IDEA_HOME
bash "$checkout/.github/scripts/ci/verify.sh" 2>&1 | tee "$parent/ci.log"
