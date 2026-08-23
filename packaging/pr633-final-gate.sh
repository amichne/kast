#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repository="${KAST_PR633_REPOSITORY:-$(cd "$script_directory/.." && pwd -P)}"
cd "$repository"

require_clean_checkout() {
  local phase="$1"
  local changes
  changes="$(git status --porcelain=v1 --untracked-files=normal)"
  if [[ -n "$changes" ]]; then
    printf 'PR 633 final gate requires a clean checkout %s:\n%s\n' "$phase" "$changes" >&2
    return 1
  fi
}

require_clean_checkout "before execution"
./gradlew pr633MergeCandidateAcceptance "$@"
require_clean_checkout "after execution"
