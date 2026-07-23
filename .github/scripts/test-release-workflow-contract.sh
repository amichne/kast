#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ci="$repo_root/.github/workflows/ci.yml"
release="$repo_root/.github/workflows/release.yml"
build="$repo_root/build.gradle.kts"
verify_assets="$repo_root/scripts/verify-release-assets.sh"
verify_state="$repo_root/scripts/verify-release-state.sh"
verify_setup="$repo_root/scripts/verify-setup-bundle.sh"
release_preflight="$(sed -n '/^  release-preflight:/,/^  bump-version:/p' "$release")"
bump_version="$(sed -n '/^  bump-version:/,/^  prepare-release:/p' "$release")"
prepare_release="$(sed -n '/^  prepare-release:/,/^  validate-jvm:/p' "$release")"
publish_maven="$(sed -n '/^  publish-maven-central:/,/^  build-cli:/p' "$release")"
publish_release="$(sed -n '/^  publish-release:/,/^  verify-release-state:/p' "$release")"
verify_release="$(sed -n '/^  verify-release-state:/,$p' "$release")"

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
require "$release" './scripts/ci-gradle-retry.sh ./gradlew \' 'headless release must invoke Gradle directly through the CI retry helper'
require "$release" 'stageHeadlessDist \' 'headless release must stage the portable distribution'
require "$release" 'buildHeadlessPortableZip \' 'headless release must build the portable zip'
require "$release" 'cp "${headless_zips[0]}" dist/headless.zip' 'headless release must publish the artifact consumed by later jobs'
require "$verify_state" 'verify-setup-bundle.sh' 'published release verification must enter through kast setup'
require "$verify_setup" '"status"[[:space:]]*:[[:space:]]*"ACTIVATED"' 'setup verification must accept pretty-printed activation JSON'
require "$verify_setup" '"status"[[:space:]]*:[[:space:]]*"CURRENT"' 'setup verification must accept pretty-printed current JSON'
reject "$release" './kast.sh' 'release workflow still depends on the deleted build wrapper'
[[ ! -e "$repo_root/kast.sh" ]] \
  || { printf '%s\n' 'error: retired kast.sh build wrapper still exists' >&2; exit 1; }

grep -Fq './gradlew test' <<<"$release_preflight" \
  || { printf '%s\n' 'error: release dispatch must validate JVM tests before creating a tag' >&2; exit 1; }
grep -Fq 'needs: [release-preflight]' <<<"$bump_version" \
  || { printf '%s\n' 'error: version tagging must depend on release preflight' >&2; exit 1; }
grep -Fq 'token: ${{ secrets.RELEASE_GITHUB_TOKEN }}' <<<"$bump_version" \
  || { printf '%s\n' 'error: version tags must use the release token so tag pushes trigger publication' >&2; exit 1; }
grep -Fq "github.event_name == 'push'" <<<"$prepare_release" \
  || { printf '%s\n' 'error: workflow dispatch must stop after pushing the release tag' >&2; exit 1; }
grep -Fq 'name: Verify Maven Central publication' <<<"$publish_maven" \
  || { printf '%s\n' 'error: Maven publication must end with authoritative remote verification' >&2; exit 1; }
grep -Fq 'continue-on-error: true' <<<"$publish_maven" \
  || { printf '%s\n' 'error: Maven publication must not block the release workflow' >&2; exit 1; }
! grep -Fq 'verify-maven-central.sh' "$verify_state" \
  || { printf '%s\n' 'error: immutable release verification must not call Maven Central' >&2; exit 1; }
! grep -Fq 'publish-maven-central' <<<"$publish_release" \
  || { printf '%s\n' 'error: immutable release publication must not depend on Maven Central' >&2; exit 1; }
! grep -Fq 'publish-maven-central' <<<"$verify_release" \
  || { printf '%s\n' 'error: immutable release verification must not depend on Maven Central' >&2; exit 1; }

for file in "$ci" "$release" "$verify_state"; do
  reject "$file" 'homebrew' 'retired Homebrew authority remains in release flow'
  reject "$file" 'kast machine' 'retired machine authority remains in release flow'
  reject "$file" 'kast repair' 'retired repair authority remains in release flow'
done

printf '%s\n' 'release setup workflow contract passed'
