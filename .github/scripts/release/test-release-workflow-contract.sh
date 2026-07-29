#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
ci="$repo_root/.github/workflows/ci.yml"
release="$repo_root/.github/workflows/release.yml"
build="$repo_root/build.gradle.kts"
verify_assets="$repo_root/scripts/release/verify-release-assets.sh"
verify_state="$repo_root/scripts/release/verify-release-state.sh"
verify_setup="$repo_root/scripts/verify-setup-bundle.sh"
release_preflight="$(sed -n '/^  release-preflight:/,/^  bump-version:/p' "$release")"
bump_version="$(sed -n '/^  bump-version:/,/^  prepare-release:/p' "$release")"
prepare_release="$(sed -n '/^  prepare-release:/,/^  validate-jvm:/p' "$release")"
build_openapi="$(sed -n '/^  build-openapi-spec:/,/^  publish-maven-central:/p' "$release")"
publish_maven="$(sed -n '/^  publish-maven-central:/,/^  build-cli:/p' "$release")"
real_repository_indexing="$(sed -n '/^  real-repository-indexing:/,/^  publish-release:/p' "$release")"
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

require "$ci" '.github/scripts/install/test-setup-contract.sh' 'CI must execute the sole setup transaction contract'
require "$ci" '--plugin-archive "$plugin_asset"' 'CI setup bundles must include the verified IDEA plugin'
require "$ci" 'scripts/verify-setup-bundle.sh' 'hosted-agent CI must enter through kast setup'
require "$build" '"setup",' 'local development refresh must invoke kast setup'
require "$build" '"--idea-plugin",' 'local development refresh must pass the IDEA plugin'

require "$release" 'for platform in linux-x64 linux-arm64 macos-x64 macos-arm64' 'release must package every supported setup platform'
require "$ci" 'cp cli-rs/target/release/kast cli-rs/target/package/kast-v0.0.0-ci-linux-x64/_kastctl' 'CI raw CLI asset must include the administrative entrypoint'
require "$ci" 'cmp -s cli-rs/target/package/kast-v0.0.0-ci-linux-x64/_kastctl cli-rs/target/package/kast-v0.0.0-ci-linux-x64/kast' 'CI must prove its multicall entrypoints are byte-identical'
require "$release" 'cp "cli-rs/target/${{ matrix.target }}/release/kast" "$staging_dir/_kastctl"' 'raw CLI assets must include the administrative entrypoint'
require "$release" 'cmp -s "$staging_dir/_kastctl" "$staging_dir/kast"' 'release must prove its multicall entrypoints are byte-identical'
require "$release" 'zip -9 -q "$GITHUB_WORKSPACE/dist/${asset_name}" _kastctl kast' 'raw CLI archives must publish only the two multicall names'
require "$release" '.github/scripts/release/agent-resource-assets.py build' 'release must build agent resources with the deterministic packager'
require "$release" '--source cli-rs/resources/kast' 'release resources must come from the embedded source'
require "$release" 'kast-codex-${tag}.tar' 'release must publish the embedded Codex marketplace'
require "$release" 'kast-claude-${tag}.tar' 'release must publish the embedded Claude marketplace'
require "$release" 'kast-copilot-${tag}.tar' 'release must publish the embedded Copilot marketplace'
require "$release" 'kast-agent-resources-provenance.json' 'release must publish embedded resource provenance'
require "$release" 'agent-resource-assets.py verify' 'release must verify resources before publication'
reject "$release" 'kagent' 'release workflow must not publish the retired kagent name'
for platform in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  require "$verify_assets" "kast-$platform-{tag}.tar.gz" "release verifier must require $platform setup bundle"
done
require "$release" '--plugin-archive "$work/kast-idea-${tag}.zip"' 'release bundles must include the release-matched IDEA plugin'
require "$release" 'scripts/verify-setup-bundle.sh' 'release validation must enter through kast setup'
require "$release" './scripts/ci-gradle-retry.sh ./gradlew \' 'headless release must invoke Gradle directly through the CI retry helper'
require "$release" 'stageHeadlessDist \' 'headless release must stage the portable distribution'
require "$release" ':backend-headless:verifyHeadlessPortableDistLayout \' 'headless release must verify the portable distribution layout'
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
! grep -Fq -- '- validate-jvm' <<<"$build_openapi" \
  || { printf '%s\n' 'error: OpenAPI build must not wait for unrelated JVM validation' >&2; exit 1; }
grep -Fq "vars.CI_AUX_IDEA_PERFORMANCE != 'optional'" <<<"$real_repository_indexing" \
  || { printf '%s\n' 'error: lean releases must skip the optional real-repository performance gate' >&2; exit 1; }
grep -Fq "vars.CI_AUX_IDEA_PERFORMANCE == 'optional'" <<<"$publish_release" \
  || { printf '%s\n' 'error: release publication must recognize the lean profile' >&2; exit 1; }
grep -Fq "needs.real-repository-indexing.result == 'skipped'" <<<"$publish_release" \
  || { printf '%s\n' 'error: publication must accept an intentionally skipped performance gate' >&2; exit 1; }
for artifact_job in build-cli build-agent-resources build-idea-plugin build-headless-backend; do
  grep -Fq -- "- $artifact_job" <<<"$publish_release" \
    || { printf 'error: %s release artifacts must remain mandatory\n' "$artifact_job" >&2; exit 1; }
done
grep -Fq 'needs.publish-release.result }}" != "success"' <<<"$verify_release" \
  || { printf '%s\n' 'error: published-state verification must require successful publication' >&2; exit 1; }
grep -Fq 'scripts/release/verify-release-state.sh' <<<"$verify_release" \
  || { printf '%s\n' 'error: published-state verification must remain mandatory' >&2; exit 1; }

for file in "$ci" "$release" "$verify_state"; do
  reject "$file" 'homebrew' 'retired Homebrew authority remains in release flow'
  reject "$file" 'kast machine' 'retired machine authority remains in release flow'
  reject "$file" 'kast repair' 'retired repair authority remains in release flow'
done

printf '%s\n' 'release setup workflow contract passed'
