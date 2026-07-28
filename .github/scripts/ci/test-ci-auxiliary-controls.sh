#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'CI auxiliary controls contract: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
ci="${repo_root}/.github/workflows/ci.yml"
controls="${repo_root}/.github/workflows/ci-auxiliary-controls.yml"

require() {
  local file="$1"
  local needle="$2"
  local description="$3"
  grep -Fq -- "$needle" "$file" || die "$description"
}

[[ -f "$controls" ]] || die "missing CI auxiliary-control workflow"

flags=(
  CI_AUX_LOCAL_AUTHORITY
  CI_AUX_RUNTIME_CONTRACTS
  CI_AUX_MAVEN_PUBLICATION
  CI_AUX_DISTRIBUTION
  CI_AUX_ANALYSIS_TRANSPORT
  CI_AUX_IDEA_PERFORMANCE
)

require "$controls" "set-one" "workflow must expose the set-one operation"
require "$controls" "apply-profile" "workflow must expose the apply-profile operation"
require "$controls" "lean) values=(false false false false false false)" "lean profile must disable every auxiliary flag"
require "$controls" "standard) values=(true true false false true false)" "standard profile must keep contract smokes only"
require "$controls" "full) values=(true true true true true true)" "full profile must enable every auxiliary flag"
require "$controls" 'gh variable set "$FLAG" --body "$VALUE" --repo "$GITHUB_REPOSITORY"' "set-one job must write the selected flag"
require "$controls" 'gh variable set "${flags[$index]}" --body "${values[$index]}" --repo "$GITHUB_REPOSITORY"' "profile job must write every flag"

for flag in "${flags[@]}"; do
  require "$controls" "- ${flag}" "workflow must offer ${flag}"
  require "$ci" "vars.${flag} != 'false'" "CI must gate an auxiliary surface with ${flag} while defaulting to enabled"
done

printf '%s\n' 'CI auxiliary controls contract passed'
