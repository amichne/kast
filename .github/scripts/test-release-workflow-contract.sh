#!/usr/bin/env bash
set -euo pipefail

release=.github/workflows/release.yml
[[ -f "$release" ]] || { echo "missing $release" >&2; exit 1; }

job() {
  sed -n "/^  $1:/,/^  $2:/p" "$release"
}

require() {
  grep -Fq -- "$2" <<<"$1" || { echo "$3" >&2; exit 1; }
}

reject() {
  ! grep -Fq -- "$2" <<<"$1" || { echo "$3" >&2; exit 1; }
}

openapi=$(job build-openapi-spec publish-maven)
real_repositories=$(job real-repository-indexing publish-release)
publish=$(job publish-release verify-release-state)
verify=$(sed -n '/^  verify-release-state:/,$p' "$release")

reject "$openapi" "- validate-jvm" "OpenAPI build must start after release preparation without waiting for JVM validation"
require "$real_repositories" "vars.CI_AUX_IDEA_PERFORMANCE != 'optional'" "Lean releases must skip the optional real-repository performance gate"
require "$publish" "vars.CI_AUX_IDEA_PERFORMANCE == 'optional'" "Publication must recognize the lean release profile"
require "$publish" "needs.real-repository-indexing.result == 'skipped'" "Publication must accept an intentionally skipped performance gate"
require "$publish" "- build-cli" "CLI release artifacts must remain mandatory"
require "$publish" "- build-idea-plugin" "IDEA plugin release artifacts must remain mandatory"
require "$publish" "- build-headless-backend" "Headless release artifacts must remain mandatory"
require "$verify" 'needs.publish-release.result }}" != "success"' "Published-state verification must fail unless publication succeeds"
require "$verify" "scripts/release/verify-release-state.sh" "Published-state verification must remain mandatory"
