#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ci="$repo_root/.github/workflows/ci.yml"
release="$repo_root/.github/workflows/release.yml"
build="$repo_root/build.gradle.kts"
verify_assets="$repo_root/scripts/verify-release-assets.sh"
verify_state="$repo_root/scripts/verify-release-state.sh"

require() {
  local file="$1" text="$2" message="$3"
  grep -Fq -- "$text" "$file" || { printf 'error: %s\n' "$message" >&2; exit 1; }
}

reject() {
  local file="$1" text="$2" message="$3"
  ! grep -Fiq -- "$text" "$file" || { printf 'error: %s\n' "$message" >&2; exit 1; }
}

require "$ci" '.github/scripts/test-setup-contract.sh' 'CI must execute the sole setup transaction contract'
require "$ci" '--plugin-archive "$plugin_asset"' 'CI setup bundles must include the verified IDEA plugin'
require "$ci" 'scripts/verify-setup-bundle.sh' 'hosted-agent CI must enter through kast setup'
require "$build" '"setup",' 'local development refresh must invoke kast setup'
require "$build" '"--source",' 'local development refresh must pass one setup bundle'

require "$release" 'for platform in linux-x64 linux-arm64 macos-x64 macos-arm64' 'release must package every supported setup platform'
for platform in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  require "$verify_assets" "kast-$platform-{tag}.tar.gz" "release verifier must require $platform setup bundle"
done
require "$release" '--plugin-archive "$work/kast-idea-${tag}.zip"' 'release bundles must include the release-matched IDEA plugin'
require "$release" 'scripts/verify-setup-bundle.sh' 'release validation must enter through kast setup'
require "$verify_state" 'verify-setup-bundle.sh' 'published release verification must enter through kast setup'

for file in "$ci" "$release" "$verify_state"; do
  reject "$file" 'homebrew' 'retired Homebrew authority remains in release flow'
  reject "$file" 'kast machine' 'retired machine authority remains in release flow'
  reject "$file" 'kast repair' 'retired repair authority remains in release flow'
done

printf '%s\n' 'release setup workflow contract passed'
