#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"

  grep -Fq -- "$expected" "$file_path" || die "${description}: missing '${expected}' in ${file_path}"
}

require_not_contains() {
  local file_path="$1"
  local unexpected="$2"
  local description="$3"

  ! grep -Fq -- "$unexpected" "$file_path" || die "${description}: found '${unexpected}' in ${file_path}"
}

require_occurrences() {
  local file_path="$1"
  local expected="$2"
  local expected_count="$3"
  local description="$4"

  local actual_count
  actual_count="$({ grep -F -- "$expected" "$file_path" || true; } | wc -l | tr -d ' ')"
  [[ "$actual_count" == "$expected_count" ]] || die "${description}: expected ${expected_count} occurrences of '${expected}' in ${file_path}, found ${actual_count}"
}

line_number_for() {
  local file_path="$1"
  local needle="$2"

  grep -nF -- "$needle" "$file_path" | head -1 | cut -d: -f1
}

require_order() {
  local file_path="$1"
  local earlier="$2"
  local later="$3"
  local description="$4"

  local earlier_line
  local later_line
  earlier_line="$(line_number_for "$file_path" "$earlier")"
  later_line="$(line_number_for "$file_path" "$later")"

  [[ -n "$earlier_line" ]] || die "${description}: missing earlier marker '${earlier}' in ${file_path}"
  [[ -n "$later_line" ]] || die "${description}: missing later marker '${later}' in ${file_path}"
  [[ "$earlier_line" -lt "$later_line" ]] || die "${description}: '${earlier}' must appear before '${later}' in ${file_path}"
}

repo_root="$(resolve_repo_root)"
ci_workflow="${repo_root}/.github/workflows/ci.yml"
release_workflow="${repo_root}/.github/workflows/release.yml"

[[ -f "$ci_workflow" ]] || die "CI workflow not found: $ci_workflow"
[[ -f "$release_workflow" ]] || die "Release workflow not found: $release_workflow"

require_contains "$ci_workflow" "Workflow release contracts" "CI must run this workflow contract check"
require_contains "$ci_workflow" "./.github/scripts/test-release-asset-verifier.sh" "CI must test the release asset verifier"
require_contains "$ci_workflow" "Analysis server transport" "CI must include an independent analysis-server transport job"
require_contains "$ci_workflow" "io.github.amichne.kast.server.AnalysisServerSocketTest" "Analysis server job must smoke the socket transport"
require_contains "$ci_workflow" "Native CLI" "CI must include a native CLI job"
require_contains "$ci_workflow" "graalvm/setup-graalvm@v1" "Native CLI job must install GraalVM"
require_contains "$ci_workflow" ":kast-cli:nativeCompile" "Native CLI job must compile the native image"
require_contains "$ci_workflow" "kast-cli/build/native/nativeCompile/kast" "Native CLI job must smoke the native binary"
require_contains "$ci_workflow" "./scripts/smoke-native-cli.sh kast-cli/build/native/nativeCompile/kast" "Native CLI job must smoke embedded agent resources"

require_contains "$release_workflow" "Generate and upload SHA256SUMS" "Release must publish aggregate checksums"
require_contains "$release_workflow" "Dispatch Homebrew tap update" "Release must dispatch the Homebrew tap update"
require_contains "$release_workflow" "Wait for Homebrew tap update" "Release must wait for the Homebrew tap update"
require_contains "$release_workflow" "gh run watch" "Release must watch the Homebrew tap workflow result"
require_contains "$release_workflow" "Package headless agent bundle" "Release must package the headless agent bundle"
require_contains "$release_workflow" "Smoke headless agent bundle" "Release must smoke the headless agent bundle"
require_contains "$release_workflow" "build-provenance-headless-agent-linux-x64.json" "Release must write provenance for the headless agent bundle"
require_contains "$release_workflow" "expected_platforms = {" "Release must validate the complete provenance platform set"
require_contains "$release_workflow" '"headless-agent-linux-x64"' "Release provenance must include the headless agent bundle"
require_contains "$release_workflow" "missing_provenance" "Release provenance validation must fail on missing entries"
require_contains "$release_workflow" "assetDigest" "Release provenance entries must include asset digests"
require_contains "$release_workflow" "expected_assets=(" "Release must validate the complete shipped asset set"
require_contains "$release_workflow" '"kast-${tag}-linux-x64.zip"' "Release must require the Linux CLI asset"
require_contains "$release_workflow" '"kast-${tag}-macos-arm64.zip"' "Release must require the macOS CLI asset"
require_contains "$release_workflow" '"kast-headless-agent-${tag}-linux-x64.zip"' "Release must require the headless agent bundle"
require_contains "$release_workflow" '"kast-intellij-${tag}.zip"' "Release must require the IntelliJ plugin asset"
require_contains "$release_workflow" '"kast-standalone-${tag}.zip"' "Release must require the standalone backend asset"
require_contains "$release_workflow" 'for asset in "${expected_assets[@]}"; do' "Release must check every expected asset before publishing checksums"
require_contains "$release_workflow" "./scripts/verify-release-assets.sh --release-dir release-assets --tag \"\$tag\"" "Release must verify assets, checksums, and provenance before publishing"
require_contains "$release_workflow" 'gh release view "$tag" >/dev/null 2>&1' "Release preparation must tolerate existing releases"
require_occurrences "$release_workflow" "if: \${{ !contains(needs.prepare-release.outputs.release_tag" 2 "Homebrew tap updates must only run for stable releases"
require_not_contains "$release_workflow" "needs: [bump-version, prepare-release]" "Release build jobs must not depend on a skipped workflow_dispatch-only job"
require_not_contains "$release_workflow" "      - bump-version" "Release publish job must not depend on a skipped workflow_dispatch-only job"
require_contains "$release_workflow" 'release_flags=("--draft=false")' "Release publication must build explicit release flags"
require_contains "$release_workflow" '[[ "$tag" == *-* ]]' "Release publication must detect prerelease tags"
require_contains "$release_workflow" 'release_flags+=(--prerelease)' "Prerelease tags must publish as prereleases"
require_contains "$release_workflow" 'release_flags+=(--latest)' "Stable tags must publish as latest releases"
require_order "$release_workflow" "Generate and upload SHA256SUMS" "Publish draft release with provenance annotation" "Release must upload checksum manifest before publishing"
require_order "$release_workflow" "Publish draft release with provenance annotation" "Dispatch Homebrew tap update" "Release must publish GitHub assets before updating Homebrew"
require_order "$release_workflow" "Dispatch Homebrew tap update" "Wait for Homebrew tap update" "Release must wait only after dispatching Homebrew"

printf '%s\n' "Release workflow contract test passed"
