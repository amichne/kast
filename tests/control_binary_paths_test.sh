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

require build.gradle.kts 'target/debug/kastctl' \
  'Gradle development setup must invoke kastctl'
require scripts/verify-setup-bundle.sh 'libexec/kastctl.*setup' \
  'bundle verification must enter setup through private kastctl'
require scripts/verify-setup-kast-install.sh 'install_dir}/libexec/kastctl' \
  'installed-runtime verification must select private kastctl'
require scripts/smoke-macos-headless-runtime.sh 'current/bin/kast' \
  'the macOS runtime smoke must demand the exact-root runtime through public kast'
require scripts/smoke-macos-headless-runtime.sh 'current/libexec/kastctl' \
  'the macOS runtime smoke must use private kastctl only for its identity snapshot'
require scripts/benchmark-native-graph.py 'current/libexec/kastctl' \
  'the JSON graph diagnostic must select private kastctl'
require scripts/release/benchmark-real-repositories.sh 'current/libexec/kastctl' \
  'the release benchmark must select private kastctl'

for file in \
  scripts/packaging/assemble-prepared-local-generation.sh \
  scripts/packaging/package-prepared-local-generation.sh \
  scripts/packaging/package-prepared-local-generation-derivatives.sh \
  scripts/packaging/package-headless-runtime.sh \
  scripts/release/verify-release-assets.sh
do
  require "$file" 'kastctl' "$file must preserve the private control entrypoint"
  require "$file" 'kast' "$file must preserve the agent entrypoint"
  require "$file" 'cmp' "$file must verify byte-identical multicall entrypoints"
done

if grep -Eq 'local/bin/(kastctl|_kastctl)' "$repo_root/install.sh"; then
  printf '%s\n' 'error: installer exposes a private control command on the user path' >&2
  exit 1
fi

if [[ -e "$repo_root/scripts/smoke-macos-idea-golden-path.sh" ]]; then
  printf '%s\n' 'error: retired foreground IDEA smoke still exists' >&2
  exit 1
fi

for retired_pattern in \
  '--backend[ =]idea' \
  'kast-idea-' \
  'updatePlugins\.xml' \
  'validate_plugin_only|bootstrap_idea|idea_log|PLUGIN_ONLY_REQUIRED|live macOS IntelliJ IDEA plugin'
do
  if rg -n -- "$retired_pattern" \
    "$repo_root/scripts/benchmark-native-graph.py" \
    "$repo_root/scripts/smoke-macos-headless-runtime.sh" 2>/dev/null
  then
    printf 'error: active runtime proof retains retired IDEA surface: %s\n' "$retired_pattern" >&2
    exit 1
  fi
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
