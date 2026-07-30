#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

require() {
  local file="$1" pattern="$2" message="$3"
  grep -Eq "$pattern" "$repo_root/$file" || {
    printf 'error: %s\n' "$message" >&2
    exit 1
  }
}

require build.gradle.kts 'target/debug/_kastctl' \
  'Gradle development setup must invoke _kastctl'
require scripts/verify-setup-bundle.sh 'bin/_kastctl.*setup' \
  'bundle verification must enter setup through _kastctl'
require scripts/verify-setup-kast-install.sh 'command -v _kastctl' \
  'installed-runtime verification must select _kastctl'
require scripts/smoke-macos-idea-golden-path.sh 'current/bin/_kastctl' \
  'the macOS runtime smoke must select _kastctl'
require scripts/benchmark-native-graph.py 'current/bin/_kastctl' \
  'the legacy JSON graph benchmark must select _kastctl'
require scripts/release/benchmark-real-repositories.sh 'current/bin/_kastctl' \
  'the release benchmark must select _kastctl'

for file in \
  scripts/packaging/assemble-prepared-local-generation.sh \
  scripts/packaging/package-prepared-local-generation.sh \
  scripts/packaging/package-prepared-local-generation-derivatives.sh \
  scripts/packaging/package-headless-runtime.sh \
  scripts/release/verify-release-assets.sh
do
  require "$file" '_kastctl' "$file must preserve the control entrypoint"
  require "$file" 'kast' "$file must preserve the agent entrypoint"
  require "$file" 'cmp' "$file must verify byte-identical multicall entrypoints"
done

if rg -n '\bkagent\b' \
  --glob '!control_binary_paths_test.sh' \
  "$repo_root/build.gradle.kts" \
  "$repo_root/scripts" \
  "$repo_root/tests" \
  "$repo_root/packaging"
then
  printf '%s\n' 'error: active packaging and verification paths retain kagent' >&2
  exit 1
fi

printf '%s\n' 'control binary path contract passed'
