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

reject() {
  local file="$1"
  local needle="$2"
  local description="$3"
  if grep -Fq -- "$needle" "$file"; then
    die "$description"
  fi
}

require_count() {
  local file="$1"
  local needle="$2"
  local expected="$3"
  local description="$4"
  local actual
  actual="$(awk -v needle="$needle" 'index($0, needle) { count++ } END { print count + 0 }' "$file")"
  [[ "$actual" -eq "$expected" ]] || die "${description}: expected ${expected}, found ${actual}"
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

criticality_owners=(1 1 1 6 1 1)

require "$controls" "set-one" "workflow must expose the set-one operation"
require "$controls" "apply-profile" "workflow must expose the apply-profile operation"
require "$controls" "lean) values=(optional optional optional optional optional optional)" "lean profile must make every auxiliary flag optional"
require "$controls" "standard) values=(required required optional optional required optional)" "standard profile must require contract smokes only"
require "$controls" "full) values=(required required required required required required)" "full profile must require every auxiliary flag"
require "$controls" 'gh variable set "$FLAG" --body "$VALUE" --repo "$GITHUB_REPOSITORY"' "set-one job must write the selected flag"
require "$controls" 'gh variable set "${flags[$index]}" --body "${values[$index]}" --repo "$GITHUB_REPOSITORY"' "profile job must write every flag"
reject "$controls" '- "true"' "set-one must not expose the old enabled value"
reject "$controls" '- "false"' "set-one must not expose the old disabled value"

for index in "${!flags[@]}"; do
  flag="${flags[$index]}"
  criticality="continue-on-error: \${{ vars.${flag} == 'optional' }}"
  require "$controls" "- ${flag}" "workflow must offer ${flag}"
  require_count "$ci" \
    "$criticality" \
    "${criticality_owners[$index]}" \
    "CI must apply ${flag} criticality to every owning job or step"
  reject "$ci" "if: vars.${flag}" "CI must not skip work with ${flag}"
done

printf '%s\n' 'CI auxiliary controls contract passed'
