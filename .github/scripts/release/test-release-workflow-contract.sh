#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
ci="$repo_root/.github/workflows/ci.yml"
ci_build="$repo_root/.github/workflows/ci-build-and-test.yml"
release="$repo_root/.github/workflows/release.yml"
cli_builder="$repo_root/.github/scripts/release/actions/build-cli/action.yml"
setup_builder="$repo_root/.github/scripts/release/actions/build-setup-bundle/action.yml"
cli_publisher="$repo_root/.github/scripts/release/actions/publish-cli/action.yml"
setup_publisher="$repo_root/.github/scripts/release/actions/publish-setup-bundle/action.yml"
build="$repo_root/build.gradle.kts"
verify_assets="$repo_root/scripts/release/verify-release-assets.sh"
verify_state="$repo_root/scripts/release/verify-release-state.sh"
verify_setup="$repo_root/scripts/verify-setup-bundle.sh"
release_preflight="$(sed -n '/^  release-preflight:/,/^  bump-version:/p' "$release")"
bump_version="$(sed -n '/^  bump-version:/,/^  prepare-release:/p' "$release")"
prepare_release="$(sed -n '/^  prepare-release:/,/^  validate-jvm:/p' "$release")"
validate_jvm="$(sed -n '/^  validate-jvm:/,/^  build-openapi-spec:/p' "$release")"
build_openapi="$(sed -n '/^  build-openapi-spec:/,/^  publish-openapi-spec:/p' "$release")"
publish_openapi="$(sed -n '/^  publish-openapi-spec:/,/^  publish-maven-central:/p' "$release")"
publish_maven="$(sed -n '/^  publish-maven-central:/,/^  build-cli-linux-x64:/p' "$release")"
build_cli="$(sed -n '/^  build-cli-linux-x64:/,/^  publish-cli-linux-x64:/p' "$release")"
publish_cli="$(sed -n '/^  publish-cli-linux-x64:/,/^  build-agent-resources:/p' "$release")"
build_agent_resources="$(sed -n '/^  build-agent-resources:/,/^  publish-agent-resources:/p' "$release")"
publish_agent_resources="$(sed -n '/^  publish-agent-resources:/,/^  build-idea-plugin:/p' "$release")"
build_idea_plugin="$(sed -n '/^  build-idea-plugin:/,/^  publish-idea-plugin:/p' "$release")"
publish_idea_plugin="$(sed -n '/^  publish-idea-plugin:/,/^  build-headless-backend:/p' "$release")"
build_headless_backend="$(sed -n '/^  build-headless-backend:/,/^  build-linux-headless-tarball:/p' "$release")"
build_linux_headless="$(sed -n '/^  build-linux-headless-tarball:/,/^  publish-linux-headless-assets:/p' "$release")"
publish_linux_headless="$(sed -n '/^  publish-linux-headless-assets:/,/^  build-setup-linux-x64:/p' "$release")"
build_setup_bundle="$(sed -n '/^  build-setup-linux-x64:/,/^  publish-setup-linux-x64:/p' "$release")"
publish_setup_bundle="$(sed -n '/^  publish-setup-linux-x64:/,/^  real-repository-indexing:/p' "$release")"
real_repository_indexing="$(sed -n '/^  real-repository-indexing:/,/^  build-release-metadata:/p' "$release")"
build_release_metadata="$(sed -n '/^  build-release-metadata:/,/^  publish-release:/p' "$release")"
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
require "$ci" 'scripts/verify-setup-bundle.sh' 'CI bundle validation must enter through KastCTL setup'
require "$build" '"setup",' 'local development refresh must invoke KastCTL setup'
require "$build" '"--source",' 'local development refresh must activate one complete setup bundle'

require "$ci" 'cp cli-rs/target/release/kast cli-rs/target/package/kast-v0.0.0-ci-linux-x64/kastctl' 'CI raw CLI asset must include the administrative entrypoint'
require "$ci" 'cmp -s cli-rs/target/package/kast-v0.0.0-ci-linux-x64/kastctl cli-rs/target/package/kast-v0.0.0-ci-linux-x64/kast' 'CI must prove its multicall entrypoints are byte-identical'
require "$cli_builder" 'cp "$binary" "$staging_dir/kastctl"' 'raw CLI assets must include the administrative entrypoint'
require "$cli_builder" 'cmp -s "$staging_dir/kastctl" "$staging_dir/kast"' 'release must prove its multicall entrypoints are byte-identical'
require "$cli_builder" 'zip -9 -q "$GITHUB_WORKSPACE/dist/${asset_name}" kastctl kast' 'raw CLI archives must publish only the two multicall names'
require "$cli_builder" '"$smoke_dir/kastctl" version | grep -Fx "Kast CLI ${{ inputs.release_version }}"' 'cached CLI builds must report the requested release version'
require "$release" 'packager_bin="${packager_dir}/kastctl"' 'Linux headless packaging must invoke the administrative entrypoint'
require "$release" "grep -F './libexec/kastctl'" 'headless runtime must keep KastCTL out of bin'
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
require "$setup_builder" '--plugin-archive "$plugin_asset"' 'release bundles must include the release-matched IDEA plugin'
require "$release" 'scripts/verify-setup-bundle.sh' 'release validation must enter through KastCTL setup'
for platform in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  grep -Fq "build-cli-$platform:" <<<"$build_cli" \
    || { printf 'error: CLI producer must address %s independently\n' "$platform" >&2; exit 1; }
  grep -Fq "asset_id: $platform" <<<"$build_cli" \
    || { printf 'error: CLI producer must select %s\n' "$platform" >&2; exit 1; }
done
[[ "$(grep -Fc 'uses: ./.github/scripts/release/actions/build-cli' <<<"$build_cli")" -eq 4 ]] \
  || { printf '%s\n' 'error: every CLI platform must use the shared build action' >&2; exit 1; }
for platform in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  grep -Fq "publish-cli-$platform:" <<<"$publish_cli" \
    || { printf 'error: CLI publisher must address %s independently\n' "$platform" >&2; exit 1; }
done
[[ "$(grep -Fc 'uses: ./.github/scripts/release/actions/publish-cli' <<<"$publish_cli")" -eq 4 ]] \
  || { printf '%s\n' 'error: every CLI platform must use the shared publisher action' >&2; exit 1; }
grep -Fq -- '- build-cli-linux-x64' <<<"$build_linux_headless" \
  || { printf '%s\n' 'error: Linux packaging must wait for its Linux x64 CLI producer' >&2; exit 1; }
for unrelated_cli in build-cli-linux-arm64 build-cli-macos-x64 build-cli-macos-arm64; do
  ! grep -Fq -- "- $unrelated_cli" <<<"$build_linux_headless" \
    || { printf 'error: Linux packaging must not wait for unrelated CLI producer %s\n' "$unrelated_cli" >&2; exit 1; }
done
for platform in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  grep -Fq "build-setup-$platform:" <<<"$build_setup_bundle" \
    || { printf 'error: setup bundle producer must address %s independently\n' "$platform" >&2; exit 1; }
  grep -Fq "platform: $platform" <<<"$build_setup_bundle" \
    || { printf 'error: setup bundle producer must select %s\n' "$platform" >&2; exit 1; }
done
[[ "$(grep -Fc 'uses: ./.github/scripts/release/actions/build-setup-bundle' <<<"$build_setup_bundle")" -eq 4 ]] \
  || { printf '%s\n' 'error: every setup platform must use the shared build action' >&2; exit 1; }
grep -Fq 'setup-bundle-${{ inputs.platform }}-${{ github.run_id }}' "$setup_builder" \
  || { printf '%s\n' 'error: setup bundle producers must retain their output for publication retries' >&2; exit 1; }
grep -Fq 'compression-level: 0' "$setup_builder" \
  || { printf '%s\n' 'error: compressed setup bundles must not be recompressed as workflow artifacts' >&2; exit 1; }
for platform in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  grep -Fq "publish-setup-$platform:" <<<"$publish_setup_bundle" \
    || { printf 'error: setup publisher must address %s independently\n' "$platform" >&2; exit 1; }
done
[[ "$(grep -Fc 'uses: ./.github/scripts/release/actions/publish-setup-bundle' <<<"$publish_setup_bundle")" -eq 4 ]] \
  || { printf '%s\n' 'error: every setup platform must use the shared publisher action' >&2; exit 1; }
grep -Fq 'name: setup-bundle-${{ inputs.platform }}-${{ github.run_id }}' "$setup_publisher" \
  || { printf '%s\n' 'error: setup publishers must consume retained setup bundles' >&2; exit 1; }
grep -Fq '.github/scripts/release/upload-immutable-release-asset.sh' "$setup_publisher" \
  || { printf '%s\n' 'error: setup publishers must use immutable upload recovery' >&2; exit 1; }
grep -Fq 'setup-bundle-provenance-${{ inputs.platform }}-${{ github.run_id }}' "$setup_publisher" \
  || { printf '%s\n' 'error: setup publishers must retain provenance for metadata-only finalization' >&2; exit 1; }
for producer in \
  "$build_openapi" \
  "$build_agent_resources" \
  "$build_idea_plugin" \
  "$build_linux_headless"
do
  ! grep -Fq '.github/scripts/release/upload-immutable-release-asset.sh' <<<"$producer" \
    || { printf '%s\n' 'error: release producers must retain bytes before publication' >&2; exit 1; }
done
! grep -Fq '.github/scripts/release/upload-immutable-release-asset.sh' "$cli_builder" \
  || { printf '%s\n' 'error: CLI producers must retain bytes before publication' >&2; exit 1; }
grep -Fq 'name: openapi-spec-${{ github.run_id }}' <<<"$build_openapi" \
  || { printf '%s\n' 'error: OpenAPI producer must retain its release bytes' >&2; exit 1; }
grep -Fq 'name: rust-cli-${{ inputs.asset_id }}-${{ github.run_id }}' "$cli_builder" \
  || { printf '%s\n' 'error: CLI producers must retain their release bytes' >&2; exit 1; }
grep -Fq 'name: agent-resources-${{ github.run_id }}' <<<"$build_agent_resources" \
  || { printf '%s\n' 'error: agent-resource producer must retain its release bytes' >&2; exit 1; }
grep -Fq 'verify-ci-artifact-ledger.py record' <<<"$build_agent_resources" \
  || { printf '%s\n' 'error: agent-resource producer must ledger every retained asset' >&2; exit 1; }
grep -Fq 'name: idea-plugin-${{ github.run_id }}' <<<"$build_idea_plugin" \
  || { printf '%s\n' 'error: IDEA plugin producer must retain its release bytes' >&2; exit 1; }
grep -Fq 'name: linux-headless-tarball-${{ github.run_id }}' <<<"$build_linux_headless" \
  || { printf '%s\n' 'error: Linux producer must retain its release bytes' >&2; exit 1; }
for publisher in \
  "$publish_openapi" \
  "$publish_agent_resources" \
  "$publish_idea_plugin" \
  "$publish_linux_headless"
do
  grep -Fq '.github/scripts/release/upload-immutable-release-asset.sh' <<<"$publisher" \
    || { printf '%s\n' 'error: retained release bytes must have a dedicated publisher' >&2; exit 1; }
  ! grep -Eq 'cargo build|./gradlew|agent-resource-assets.py build|developer release package|:backend-idea:buildPlugin' <<<"$publisher" \
    || { printf '%s\n' 'error: release publishers must not rebuild retained bytes' >&2; exit 1; }
done
grep -Fq '.github/scripts/release/upload-immutable-release-asset.sh' "$cli_publisher" \
  || { printf '%s\n' 'error: retained CLI bytes must have a dedicated publisher' >&2; exit 1; }
! grep -Eq 'cargo build|./gradlew' "$cli_publisher" \
  || { printf '%s\n' 'error: CLI publishers must not rebuild retained bytes' >&2; exit 1; }
grep -Fq 'name: openapi-spec-${{ github.run_id }}' <<<"$publish_openapi" \
  || { printf '%s\n' 'error: OpenAPI publisher must consume its retained artifact' >&2; exit 1; }
grep -Fq 'name: rust-cli-${{ inputs.asset_id }}-${{ github.run_id }}' "$cli_publisher" \
  || { printf '%s\n' 'error: CLI publisher must consume its retained artifact' >&2; exit 1; }
grep -Fq 'name: agent-resources-${{ github.run_id }}' <<<"$publish_agent_resources" \
  || { printf '%s\n' 'error: agent-resource publisher must consume its retained artifact' >&2; exit 1; }
grep -Fq 'verify-ci-artifact-ledger.py verify' <<<"$publish_agent_resources" \
  || { printf '%s\n' 'error: agent-resource publisher must verify retained bytes against their ledger' >&2; exit 1; }
grep -Fq 'name: idea-plugin-${{ github.run_id }}' <<<"$publish_idea_plugin" \
  || { printf '%s\n' 'error: IDEA plugin publisher must consume its retained artifact' >&2; exit 1; }
grep -Fq 'name: linux-headless-tarball-${{ github.run_id }}' <<<"$publish_linux_headless" \
  || { printf '%s\n' 'error: Linux publisher must consume its retained artifact' >&2; exit 1; }
grep -Fq 'name: idea-plugin-${{ github.run_id }}' "$setup_builder" \
  || { printf '%s\n' 'error: setup builders must consume the retained IDEA plugin' >&2; exit 1; }
! grep -Fq 'gh release download' "$setup_builder" \
  || { printf '%s\n' 'error: setup builders must not use a draft release as artifact transport' >&2; exit 1; }
grep -Fq 'name: idea-plugin-${{ github.run_id }}' <<<"$build_linux_headless" \
  || { printf '%s\n' 'error: Linux builders must consume the retained IDEA plugin' >&2; exit 1; }
! grep -Fq 'gh release download' <<<"$build_linux_headless" \
  || { printf '%s\n' 'error: Linux builders must not use a draft release as artifact transport' >&2; exit 1; }
[[ "$(grep -Fc 'uses: actions/upload-artifact@v6' <<<"$build_headless_backend")" -eq 1 ]] \
  || { printf '%s\n' 'error: headless producer outputs must be retained by one atomic upload' >&2; exit 1; }
grep -Fq 'dist/gradle-ro-cache/' <<<"$build_headless_backend" \
  || { printf '%s\n' 'error: the atomic headless artifact must retain the Gradle cache' >&2; exit 1; }
! grep -Fq 'name: gradle-ro-cache-${{ github.run_id }}' <<<"$release" \
  || { printf '%s\n' 'error: the Gradle cache must not use a second collision-prone artifact upload' >&2; exit 1; }
grep -Fq 'name: headless-backend-${{ github.run_id }}' <<<"$publish_linux_headless" \
  || { printf '%s\n' 'error: Linux publication must consume the atomic headless artifact' >&2; exit 1; }
! grep -Fq 'developer release package ubuntu-debian-bundle' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: publication must not rebuild setup bundles' >&2; exit 1; }
! grep -Fq 'gh release download "$tag" --dir release-assets' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: publication must not download full release assets' >&2; exit 1; }
grep -Fq 'pattern: cli-provenance-*-${{ github.run_id }}' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: publication must consume metadata-only CLI provenance artifacts' >&2; exit 1; }
grep -Fq 'pattern: linux-headless-provenance-*' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: publication must consume metadata-only Linux provenance artifacts' >&2; exit 1; }
! grep -Fq 'pattern: rust-cli-*-${{ github.run_id }}' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: publication must not download full CLI artifacts' >&2; exit 1; }
! grep -Fq 'pattern: linux-headless-tarball-*' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: publication must not download the full Linux distribution artifact' >&2; exit 1; }
! grep -Fq 'verify-ci-artifact-ledger.py verify' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: metadata-only publication must not consume receipt-owned product bytes' >&2; exit 1; }
grep -Fq '.github/scripts/release/metadata/render-release-checksums.py' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: publication must render checksums from verified provenance metadata' >&2; exit 1; }
grep -Fq "timeout 60s gh release download" <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: required sidecar downloads must have a bounded timeout' >&2; exit 1; }
grep -Fq 'sleep "$attempt"' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: sidecar download retries must back off' >&2; exit 1; }
grep -Fq 'name: Generate SHA256SUMS' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: provenance must be validated before final metadata upload' >&2; exit 1; }
grep -Fq 'name: release-metadata-${{ github.run_id }}' <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: validated release metadata must be retained before publication' >&2; exit 1; }
grep -Fq 'name: release-metadata-${{ github.run_id }}' <<<"$publish_release" \
  || { printf '%s\n' 'error: final publication must consume retained metadata' >&2; exit 1; }
grep -Fq '.github/scripts/release/upload-immutable-release-asset.sh' <<<"$publish_release" \
  || { printf '%s\n' 'error: final publication must use immutable metadata upload recovery' >&2; exit 1; }
! grep -Fq '.github/scripts/release/metadata/render-release-checksums.py' <<<"$publish_release" \
  || { printf '%s\n' 'error: a publish retry must not regenerate retained metadata' >&2; exit 1; }
grep -Fq 'if [[ "$release_body" != *"$provenance_marker"* ]]' <<<"$publish_release" \
  || { printf '%s\n' 'error: release annotation retries must be idempotent' >&2; exit 1; }
for retained_artifact_section in \
  "$build_openapi" \
  "$build_agent_resources" \
  "$build_idea_plugin" \
  "$build_linux_headless" \
  "$build_release_metadata"
do
  grep -Fq 'retention-days: 30' <<<"$retained_artifact_section" \
    || { printf '%s\n' 'error: retained release artifacts must survive the 30-day rerun window' >&2; exit 1; }
done
for retained_artifact_action in \
  "$cli_builder" \
  "$setup_builder" \
  "$cli_publisher" \
  "$setup_publisher"
do
  grep -Fq 'retention-days: 30' "$retained_artifact_action" \
    || { printf '%s\n' 'error: retained release artifacts must survive the 30-day rerun window' >&2; exit 1; }
done
for workflow_surface in \
  "$release" \
  "$cli_builder" \
  "$setup_builder" \
  "$cli_publisher" \
  "$setup_publisher"
do
  ! grep -Eq 'retention-days:[[:space:]]+3([[:space:]]|$)' "$workflow_surface" \
    || { printf '%s\n' 'error: release artifacts expire before the supported rerun window' >&2; exit 1; }
done
require "$release" './scripts/ci-gradle-retry.sh ./gradlew \' 'headless release must invoke Gradle directly through the CI retry helper'
require "$release" 'stageHeadlessDist \' 'headless release must stage the portable distribution'
require "$release" ':backend-headless:verifyHeadlessPortableDistLayout \' 'headless release must verify the portable distribution layout'
require "$release" 'buildHeadlessPortableZip \' 'headless release must build the portable zip'
require "$release" 'cp "${headless_zips[0]}" dist/headless.zip' 'headless release must publish the artifact consumed by later jobs'
require "$verify_state" 'verify-setup-bundle.sh' 'published release verification must enter through KastCTL setup'
require "$verify_setup" '"status"[[:space:]]*:[[:space:]]*"ACTIVATED"' 'setup verification must accept pretty-printed activation JSON'
require "$verify_setup" '"status"[[:space:]]*:[[:space:]]*"CURRENT"' 'setup verification must accept pretty-printed current JSON'
reject "$release" './kast.sh' 'release workflow still depends on the deleted build wrapper'
[[ ! -e "$repo_root/kast.sh" ]] \
  || { printf '%s\n' 'error: retired kast.sh build wrapper still exists' >&2; exit 1; }

for exact_ci_evidence in \
  'actions: read' \
  'GH_TOKEN: ${{ github.token }}' \
  'GITHUB_EVENT_NAME' \
  'refs/heads/main' \
  'gh api --method GET' \
  'actions/workflows/ci.yml/runs' \
  'actions/runs/${ci_run_id}/artifacts' \
  'ci-artifact-ledger-maven-publication' \
  '-f branch=main' \
  '-f event=push' \
  '-f head_sha="$GITHUB_SHA"' \
  '-f status=success' \
  '.head_sha == $sha' \
  '.head_branch == "main"' \
  '.path == ".github/workflows/ci.yml"' \
  '.event == "push"' \
  '.status == "completed"' \
  '.conclusion == "success"'
do
  grep -Fq -- "$exact_ci_evidence" <<<"$release_preflight" \
    || { printf 'error: release preflight must require exact-source CI evidence: %s\n' "$exact_ci_evidence" >&2; exit 1; }
done
grep -Fq '.expired == false' <<<"$release_preflight" \
  || { printf '%s\n' 'error: release preflight must reject expired exact-source CI evidence' >&2; exit 1; }
for jvm_suite in \
  ':backend-shared:test' \
  ':index-store:test'
do
  require "$ci_build" "$jvm_suite" "exact-source CI must run $jvm_suite before release"
done
setup_job_gate="$(sed -n '/ci_jobs=/,/ci_artifacts=/p' <<<"$release_preflight")"
for setup_job_evidence in \
  'actions/runs/${ci_run_id}/jobs' \
  '.name == "Runtime command and bundle contracts"' \
  '.status == "completed"' \
  '.conclusion == "success"' \
  'error: exact-source CI setup contract did not pass'
do
  grep -Fq -- "$setup_job_evidence" <<<"$setup_job_gate" \
    || { printf 'error: release preflight must require setup-contract job evidence: %s\n' "$setup_job_evidence" >&2; exit 1; }
done
for duplicate_ci_work in \
  './gradlew test' \
  'actions/setup-java' \
  'gradle/actions/setup-gradle' \
  'dtolnay/rust-toolchain' \
  'Swatinem/rust-cache' \
  'test-setup-contract.sh'
do
  ! grep -Fq -- "$duplicate_ci_work" <<<"$release_preflight" \
    || { printf 'error: release preflight must reuse green CI instead of rerunning %s\n' "$duplicate_ci_work" >&2; exit 1; }
done
grep -Fq 'key: idea-plugin-inputs-${{ runner.os }}-${{ hashFiles('\''gradle/libs.versions.toml'\'') }}' <<<"$build_idea_plugin" \
  || { printf '%s\n' 'error: release IDEA plugin build must reuse the exact CI input cache' >&2; exit 1; }
require "$ci" 'key: idea-plugin-inputs-${{ runner.os }}-${{ hashFiles('\''gradle/libs.versions.toml'\'') }}' \
  'CI must own the IDEA plugin input cache reused by release'
grep -Fq '~/.cache/pluginVerifier/ides' <<<"$build_idea_plugin" \
  || { printf '%s\n' 'error: release IDEA plugin cache paths must match the CI cache version' >&2; exit 1; }
grep -Fq 'key: intellij-runtime-all-${{ runner.os }}-${{ hashFiles('\''gradle/libs.versions.toml'\'') }}' <<<"$build_headless_backend" \
  || { printf '%s\n' 'error: release headless build must reuse the exact CI runtime cache' >&2; exit 1; }
require "$ci_build" 'key: intellij-runtime-all-${{ runner.os }}-${{ hashFiles('\''gradle/libs.versions.toml'\'') }}' \
  'CI must own the full IntelliJ runtime cache reused by release'
for runtime_cache_path in \
  '~/.gradle/kast/headless-idea-distributions' \
  '~/.gradle/kast/shared-idea-distributions' \
  '~/.gradle/kast/backend-idea-distributions'
do
  grep -Fq "$runtime_cache_path" <<<"$build_headless_backend" \
    || { printf 'error: release headless cache must match CI path: %s\n' "$runtime_cache_path" >&2; exit 1; }
done
! grep -Fq 'release-idea-plugin-inputs-' <<<"$build_idea_plugin" \
  || { printf '%s\n' 'error: release IDEA plugin build must not fork the immutable CI input cache' >&2; exit 1; }
! grep -Fq 'release-headless-runtime-' <<<"$build_headless_backend" \
  || { printf '%s\n' 'error: release headless build must not fork the immutable CI runtime cache' >&2; exit 1; }
for ci_maven_evidence in \
  'source_ci_run_id: ${{ needs.release-preflight.outputs.run_id }}' \
  'name: ci-artifact-ledger-maven-publication' \
  'run-id: ${{ needs.prepare-release.outputs.source_ci_run_id }}' \
  'github-token: ${{ github.token }}' \
  '.workflowRunId == $run_id' \
  '.sourceRef == "refs/heads/main"' \
  '--require-kind ci-maven-publication-validation'
do
  grep -Fq -- "$ci_maven_evidence" "$release" \
    || { printf 'error: release must reuse exact-source CI Maven evidence: %s\n' "$ci_maven_evidence" >&2; exit 1; }
done
for duplicate_maven_validation in \
  './gradlew' \
  'actions/setup-java' \
  'gradle/actions/setup-gradle' \
  'release-maven-publication-validation.txt'
do
  ! grep -Fq -- "$duplicate_maven_validation" <<<"$validate_jvm" \
    || { printf 'error: JVM validation must reuse CI instead of rebuilding %s\n' "$duplicate_maven_validation" >&2; exit 1; }
done
grep -Fq 'retention-days: 30' <<<"$validate_jvm" \
  || { printf '%s\n' 'error: reused Maven evidence must survive release retries' >&2; exit 1; }
grep -Fq '"exact-source-ci-maven-validation"' "$repo_root/.github/scripts/release/metadata/release-workflow-model.json" \
  || { printf '%s\n' 'error: release graph must name the reused exact-source CI Maven proof honestly' >&2; exit 1; }
! grep -Fq '"release-jvm-validation"' "$repo_root/.github/scripts/release/metadata/release-workflow-model.json" \
  || { printf '%s\n' 'error: release graph still claims a rebuilt release JVM validation proof' >&2; exit 1; }
grep -Fq "[[ \"\${{ inputs.asset_id }}\" == 'linux-x64' ]]" "$cli_builder" \
  || { printf '%s\n' 'error: the Linux x64 release build must select the CI-compatible cache and target layout' >&2; exit 1; }
grep -Fq 'shared-key: ${{ inputs.cache_key }}' "$cli_builder" \
  || { printf '%s\n' 'error: release CLI producers must select an explicit trusted cache' >&2; exit 1; }
grep -Fq 'cache_key: source-bound-cli-release' <<<"$build_cli" \
  || { printf '%s\n' 'error: the Linux x64 release build must reuse the trusted main CI cache' >&2; exit 1; }
grep -Fq 'bin="target/release/kast"' "$cli_builder" \
  || { printf '%s\n' 'error: the Linux x64 release build must use the same target layout as main CI' >&2; exit 1; }
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
grep -Fq "vars.CI_AUX_IDEA_PERFORMANCE == 'optional'" <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: release publication must recognize the lean profile' >&2; exit 1; }
grep -Fq "needs.real-repository-indexing.result == 'skipped'" <<<"$build_release_metadata" \
  || { printf '%s\n' 'error: publication must accept an intentionally skipped performance gate' >&2; exit 1; }
for artifact_job in \
  publish-openapi-spec \
  publish-cli-linux-x64 \
  publish-cli-linux-arm64 \
  publish-cli-macos-x64 \
  publish-cli-macos-arm64 \
  publish-agent-resources \
  publish-idea-plugin \
  publish-linux-headless-assets \
  publish-setup-linux-x64 \
  publish-setup-linux-arm64 \
  publish-setup-macos-x64 \
  publish-setup-macos-arm64
do
  grep -Fq -- "- $artifact_job" <<<"$build_release_metadata" \
    || { printf 'error: %s release publication must remain mandatory\n' "$artifact_job" >&2; exit 1; }
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

reject "$release" '--clobber' 'release assets must never bypass immutable upload recovery'
"$repo_root/.github/scripts/release/test-upload-immutable-release-asset.sh"
python3 "$repo_root/.github/scripts/release/metadata/test-render-release-checksums.py"
python3 - \
  "$repo_root/.github/scripts/release/metadata/release-workflow-model.json" \
  "$release" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

model = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
release_bytes = Path(sys.argv[2]).read_bytes()
if model.get("schemaVersion") != 1 or model.get("evidenceMode") != "structural-only":
    raise SystemExit("error: release graph must use structural-only evidence")
if model.get("provenance", {}).get("sourceSha256") != hashlib.sha256(release_bytes).hexdigest():
    raise SystemExit("error: release graph must bind to the exact release workflow")
encoded = json.dumps(model)
if "durationSamplesSeconds" in encoded or "observedWorkflowDuration" in encoded:
    raise SystemExit("error: release graph must not invent candidate timing samples")
if "publishesReleaseAssets" in encoded:
    raise SystemExit("error: release graph must distinguish product assets from metadata assets")

tasks = {task["id"]: task for task in model["tasks"]}
if len(tasks) != len(model["tasks"]):
    raise SystemExit("error: release graph task IDs must be unique")
for task_id, task in tasks.items():
    unknown = set(task["needs"]) - set(tasks)
    if unknown:
        raise SystemExit(f"error: {task_id} has unknown dependencies: {sorted(unknown)}")

cli_builds = {
    "build-cli-linux-x64",
    "build-cli-linux-arm64",
    "build-cli-macos-x64",
    "build-cli-macos-arm64",
}
cli_publishers = {
    "publish-cli-linux-x64",
    "publish-cli-linux-arm64",
    "publish-cli-macos-x64",
    "publish-cli-macos-arm64",
}
setup_builds = {
    "build-setup-linux-x64",
    "build-setup-linux-arm64",
    "build-setup-macos-x64",
    "build-setup-macos-arm64",
}
setup_publishers = {
    "publish-setup-linux-x64",
    "publish-setup-linux-arm64",
    "publish-setup-macos-x64",
    "publish-setup-macos-arm64",
}
repository_gates = {
    "real-repository-indexing-ktor",
    "real-repository-indexing-spring-boot",
    "real-repository-indexing-okhttp",
}
expected_tasks = {
    "prepare-release",
    "validate-jvm",
    "publish-maven-central",
    "build-openapi-spec",
    "publish-openapi-spec",
    "build-agent-resources",
    "publish-agent-resources",
    "build-idea-plugin",
    "publish-idea-plugin",
    "build-headless-backend",
    "build-linux-headless-tarball",
    "publish-linux-headless-assets",
    "build-release-metadata",
    "publish-release",
    "verify-release-state",
} | cli_builds | cli_publishers | setup_builds | setup_publishers | repository_gates
if set(tasks) != expected_tasks:
    raise SystemExit("error: release graph must expand every required job and matrix cell")

fixed_needs = {
    "prepare-release": set(),
    "validate-jvm": {"prepare-release"},
    "publish-maven-central": {"prepare-release", "validate-jvm"},
    "build-openapi-spec": {"prepare-release"},
    "publish-openapi-spec": {"prepare-release", "build-openapi-spec"},
    "build-agent-resources": {"prepare-release"},
    "publish-agent-resources": {"prepare-release", "build-agent-resources"},
    "build-idea-plugin": {"prepare-release"},
    "publish-idea-plugin": {"prepare-release", "build-idea-plugin"},
    "build-headless-backend": {"prepare-release"},
    "build-linux-headless-tarball": {
        "prepare-release",
        "build-cli-linux-x64",
        "build-headless-backend",
        "build-idea-plugin",
    },
    "publish-linux-headless-assets": {
        "prepare-release",
        "build-linux-headless-tarball",
    },
}
for task_id, expected in fixed_needs.items():
    if set(tasks[task_id]["needs"]) != expected:
        raise SystemExit(f"error: {task_id} has the wrong dependencies")
for task_id in cli_builds:
    if set(tasks[task_id]["needs"]) != {"prepare-release"}:
        raise SystemExit(f"error: {task_id} has the wrong producer dependencies")
for task_id in cli_publishers:
    platform = task_id.removeprefix("publish-cli-")
    if set(tasks[task_id]["needs"]) != {
        "prepare-release",
        f"build-cli-{platform}",
    }:
        raise SystemExit(f"error: {task_id} must wait only for its retained CLI asset")
for task_id in setup_builds:
    platform = task_id.removeprefix("build-setup-")
    expected = {
        "prepare-release",
        "build-cli-linux-x64",
        f"build-cli-{platform}",
        "build-idea-plugin",
        "build-headless-backend",
    }
    if set(tasks[task_id]["needs"]) != expected:
        raise SystemExit(f"error: {task_id} must wait only for its actual CLI inputs")
for task_id in setup_publishers:
    task = tasks[task_id]
    platform = task_id.removeprefix("publish-setup-")
    if set(task["needs"]) != {
        "prepare-release",
        f"build-setup-{platform}",
    }:
        raise SystemExit(f"error: {task_id} must wait only for its retained setup bundle")
    if not task["consumesRetainedAssets"] or not task["publishesProductAssets"]:
        raise SystemExit(f"error: {task_id} must publish retained bytes")
artifact_publishers = {
    "publish-openapi-spec",
    "publish-agent-resources",
    "publish-idea-plugin",
    "publish-linux-headless-assets",
} | cli_publishers | setup_publishers
for task_id in artifact_publishers:
    task = tasks[task_id]
    if (
        not task["consumesRetainedAssets"]
        or not task["consumesProductBytes"]
        or not task["publishesProductAssets"]
        or task["publishesMetadataAssets"]
    ):
        raise SystemExit(f"error: {task_id} must publish only retained product bytes")
for task_id in repository_gates:
    if set(tasks[task_id]["needs"]) != {
        "prepare-release",
        "build-linux-headless-tarball",
    }:
        raise SystemExit(f"error: {task_id} has the wrong release gate dependencies")

metadata_builder = tasks["build-release-metadata"]
expected_metadata_needs = {
    "prepare-release",
    "validate-jvm",
    "publish-openapi-spec",
    "publish-agent-resources",
    "publish-idea-plugin",
    "publish-linux-headless-assets",
} | cli_publishers | setup_publishers | repository_gates
if set(metadata_builder["needs"]) != expected_metadata_needs:
    raise SystemExit("error: metadata finalization must wait for every asset publisher")
if (
    metadata_builder["consumesProductBytes"]
    or metadata_builder["publishesProductAssets"]
    or metadata_builder["publishesMetadataAssets"]
):
    raise SystemExit("error: release metadata must be built without product bytes")

finalizer = tasks["publish-release"]
if set(finalizer["needs"]) != {"prepare-release", "build-release-metadata"}:
    raise SystemExit("error: final publication must consume the retained metadata artifact")
if (
    finalizer["consumesProductBytes"]
    or finalizer["publishesProductAssets"]
    or not finalizer["publishesMetadataAssets"]
):
    raise SystemExit("error: release finalization must remain metadata-only")
if set(tasks["verify-release-state"]["needs"]) != {
    "prepare-release",
    "publish-release",
}:
    raise SystemExit("error: published-state verification must follow finalization")

remaining = set(tasks)
resolved = set()
while remaining:
    ready = {
        task_id for task_id in remaining
        if set(tasks[task_id]["needs"]) <= resolved
    }
    if not ready:
        raise SystemExit("error: release graph contains a dependency cycle")
    resolved |= ready
    remaining -= ready

output_owners = {}
for task_id, task in tasks.items():
    for output in task["outputs"]:
        if output in output_owners:
            raise SystemExit(f"error: duplicate release graph output: {output}")
        output_owners[output] = task_id
if set(output_owners) != set(model["requiredOutputs"]):
    raise SystemExit("error: release graph outputs must match the required proof set")
PY

printf '%s\n' 'release setup workflow contract passed'
