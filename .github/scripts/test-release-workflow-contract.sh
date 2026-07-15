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

require_order() {
  local file_path="$1"
  local earlier="$2"
  local later="$3"
  local description="$4"
  local earlier_line
  local later_line
  earlier_line="$(grep -nF -- "$earlier" "$file_path" | head -1 | cut -d: -f1)"
  later_line="$(grep -nF -- "$later" "$file_path" | head -1 | cut -d: -f1)"
  [[ -n "$earlier_line" ]] || die "${description}: missing earlier marker '${earlier}'"
  [[ -n "$later_line" ]] || die "${description}: missing later marker '${later}'"
  [[ "$earlier_line" -lt "$later_line" ]] || die "${description}: '${earlier}' must appear before '${later}'"
}

require_block_order() {
  local file_path="$1"
  local block_start="$2"
  local block_end="$3"
  local earlier="$4"
  local later="$5"
  local description="$6"
  local block
  local earlier_line
  local later_line
  block="$(
    awk -v block_start="$block_start" -v block_end="$block_end" '
      index($0, block_start) { in_block = 1 }
      in_block && index($0, block_end) && !index($0, block_start) { exit }
      in_block { print }
    ' "$file_path"
  )"
  [[ -n "$block" ]] || die "${description}: missing block '${block_start}' in ${file_path}"
  earlier_line="$(grep -nF -- "$earlier" <<< "$block" | head -1 | cut -d: -f1)"
  later_line="$(grep -nF -- "$later" <<< "$block" | head -1 | cut -d: -f1)"
  [[ -n "$earlier_line" ]] || die "${description}: missing earlier marker '${earlier}' in '${block_start}' block"
  [[ -n "$later_line" ]] || die "${description}: missing later marker '${later}' in '${block_start}' block"
  [[ "$earlier_line" -lt "$later_line" ]] || die "${description}: '${earlier}' must appear before '${later}' in '${block_start}' block"
}

require_block_contains() {
  local file_path="$1"
  local block_start="$2"
  local block_end="$3"
  local expected="$4"
  local description="$5"
  local block
  block="$(
    awk -v block_start="$block_start" -v block_end="$block_end" '
      index($0, block_start) { in_block = 1 }
      in_block && index($0, block_end) && !index($0, block_start) { exit }
      in_block { print }
    ' "$file_path"
  )"
  [[ -n "$block" ]] || die "${description}: missing block '${block_start}' in ${file_path}"
  grep -Fq -- "$expected" <<< "$block" || die "${description}: missing '${expected}' in '${block_start}' block"
}

require_block_not_contains() {
  local file_path="$1"
  local block_start="$2"
  local block_end="$3"
  local unexpected="$4"
  local description="$5"
  local block
  block="$(
    awk -v block_start="$block_start" -v block_end="$block_end" '
      index($0, block_start) { in_block = 1 }
      in_block && index($0, block_end) && !index($0, block_start) { exit }
      in_block { print }
    ' "$file_path"
  )"
  [[ -n "$block" ]] || die "${description}: missing block '${block_start}' in ${file_path}"
  ! grep -Fq -- "$unexpected" <<< "$block" || die "${description}: found '${unexpected}' in '${block_start}' block"
}

repo_root="$(resolve_repo_root)"
ci_workflow="${repo_root}/.github/workflows/ci.yml"
ci_build_and_test_workflow="${repo_root}/.github/workflows/ci-build-and-test.yml"
release_workflow="${repo_root}/.github/workflows/release.yml"
snapshot_workflow="${repo_root}/.github/workflows/snapshot.yml"
docs_workflow="${repo_root}/.github/workflows/docs.yml"
seed_gradle_ro_cache_workflow="${repo_root}/.github/workflows/seed-gradle-ro-cache.yml"
root_build_file="${repo_root}/build.gradle.kts"
gradle_properties="${repo_root}/gradle.properties"
version_catalog="${repo_root}/gradle/libs.versions.toml"
build_logic="${repo_root}/build-logic/build.gradle.kts"
publishing_plugin="${repo_root}/build-logic/src/main/kotlin/kast.publishing.gradle.kts"
published_library_plugin="${repo_root}/build-logic/src/main/kotlin/kast.published-library.gradle.kts"
publishing_conventions="${repo_root}/build-logic/src/main/kotlin/KastPublishingConventions.kt"
homebrew_test="${repo_root}/packaging/homebrew/scripts/test-formulas.py"
release_provenance_assembler="${repo_root}/scripts/assemble-release-provenance.py"
ci_artifact_ledger_verifier="${repo_root}/scripts/verify-ci-artifact-ledger.py"
release_asset_verifier="${repo_root}/scripts/verify-release-assets.sh"
idea_plugin_artifact_verifier="${repo_root}/scripts/verify-idea-plugin-artifact.py"
immutable_release_asset_uploader="${repo_root}/.github/scripts/upload-immutable-release-asset.sh"
idea_plugin_signing_contract="${repo_root}/.github/scripts/test-idea-plugin-signing-contract.sh"
release_state_verifier="${repo_root}/scripts/verify-release-state.sh"
maven_central_verifier="${repo_root}/scripts/verify-maven-central.sh"
ubuntu_debian_validator="${repo_root}/scripts/validate-ubuntu-debian-bundle-in-docker.sh"
headless_runtime_packager="${repo_root}/scripts/package-headless-runtime.sh"
gradle_ro_cache_packager="${repo_root}/scripts/package-gradle-ro-cache.sh"
setup_kast_verifier="${repo_root}/scripts/verify-setup-kast-install.sh"
ci_gradle_retry="${repo_root}/scripts/ci-gradle-retry.sh"
ci_gradle_retry_test="${repo_root}/.github/scripts/test-ci-gradle-retry.sh"
headless_packager_test="${repo_root}/.github/scripts/test-headless-runtime-packagers.sh"
ci_artifact_ledger_test="${repo_root}/.github/scripts/test-ci-artifact-ledger.sh"
runtime_artifact_contract="${repo_root}/docs/distribute/runtime-artifact-contract.md"
release_and_mirror_doc="${repo_root}/docs/distribute/release-and-mirror.md"
kast_script="${repo_root}/kast.sh"

for path in \
  "$ci_workflow" \
  "$release_workflow" \
  "$snapshot_workflow" \
  "$docs_workflow" \
  "$seed_gradle_ro_cache_workflow" \
  "$root_build_file" \
  "$gradle_properties" \
  "$version_catalog" \
  "$build_logic" \
  "$publishing_plugin" \
  "$published_library_plugin" \
  "$publishing_conventions" \
  "$homebrew_test" \
  "$release_provenance_assembler" \
  "$ci_artifact_ledger_verifier" \
  "$release_asset_verifier" \
  "$idea_plugin_artifact_verifier" \
  "$immutable_release_asset_uploader" \
  "$idea_plugin_signing_contract" \
  "$release_state_verifier" \
  "$maven_central_verifier" \
  "$ubuntu_debian_validator" \
  "$headless_runtime_packager" \
  "$gradle_ro_cache_packager" \
  "$setup_kast_verifier" \
  "$ci_gradle_retry" \
  "$ci_gradle_retry_test" \
  "$headless_packager_test" \
  "$ci_artifact_ledger_test" \
  "$runtime_artifact_contract" \
  "$release_and_mirror_doc" \
  "$kast_script"
do
  [[ -f "$path" || -x "$path" ]] || die "Required release file is missing: $path"
done

[[ ! -e "${repo_root}/setup-kast" ]] || die "setup-kast action source must live in amichne/kast-action, not this repository"
[[ ! -e "${repo_root}/.github/scripts/test-setup-kast-action.sh" ]] || die "setup-kast fixture tests must live in amichne/kast-action"
[[ ! -e "${repo_root}/.github/workflows/copilot-setup-steps.yml" ]] || die "GitHub coding-agent setup workflow is obsolete"
[[ ! -e "${repo_root}/.github/workflows/claude.yml" ]] || die "Provider-specific assistant trigger workflows are outside the V1 GitHub surface"
[[ ! -e "${repo_root}/docs/distribute/setup-kast-action.md" ]] || die "Detailed action docs must live in the kast-action repository"

for workflow in "$ci_workflow" "$ci_build_and_test_workflow" "$release_workflow" "$snapshot_workflow" "$docs_workflow" "$seed_gradle_ro_cache_workflow"; do
  require_not_contains "$workflow" "actions/cache@v4" "Workflow actions must not use the Node 20 cache action"
  require_not_contains "$workflow" "actions/upload-artifact@v4" "Workflow actions must not use the Node 20 upload-artifact action"
  require_not_contains "$workflow" "actions/download-artifact@v4" "Workflow actions must not use the Node 20 download-artifact action"
done

require_contains "$version_catalog" "vanniktech-maven-publish-plugin" "Version catalog must declare Vanniktech Maven Publish"
require_contains "$build_logic" "vanniktech-maven-publish-plugin" "Build logic must depend on the Maven Publish plugin"
require_contains "$publishing_plugin" "publishToMavenCentral" "Publishing convention must configure Maven Central"
require_contains "$publishing_plugin" "kastPublishing" "Publishing convention must expose the Kast publishing extension"
require_contains "$published_library_plugin" 'id("kast.kotlin-library")' "Published libraries must also be Kotlin libraries"
require_contains "$publishing_conventions" "GitHubPackages" "Publishing convention must support GitHub Packages"
require_contains "$publishing_conventions" "signing.gnupg.keyName" "Publishing convention must support GPG signing"
require_contains "$root_build_file" 'providers.gradleProperty("version")' "Root build must allow release workflows to override version"
require_contains "$gradle_properties" "POM_SCM_URL=https://github.com/amichne/kast" "POM metadata must point at the monorepo"
require_contains "$gradle_properties" "org.gradle.caching=true" "Gradle build cache must stay enabled for CI"

require_contains "${repo_root}/analysis-api/build.gradle.kts" 'artifactId.set("kast-analysis-api")' "analysis-api must publish the public Maven artifact"
require_contains "${repo_root}/analysis-server/build.gradle.kts" 'artifactId.set("kast-analysis-server")' "analysis-server must publish the public Maven artifact"
require_contains "${repo_root}/index-store/build.gradle.kts" 'artifactId.set("kast-index-store")' "index-store must publish the public Maven artifact"
require_not_contains "${repo_root}/backend-headless/build.gradle.kts" "kastPublishing" "Headless backend must remain release-asset-only"
require_not_contains "${repo_root}/backend-idea/build.gradle.kts" "kastPublishing" "IDEA plugin must remain release-asset-only"
require_contains "${repo_root}/backend-idea/build.gradle.kts" 'providers.environmentVariable("PRIVATE_KEY")' "IDEA plugin signing must read the private key only from the environment"
require_contains "${repo_root}/backend-idea/build.gradle.kts" 'providers.environmentVariable("PRIVATE_KEY_PASSWORD")' "IDEA plugin signing must read the private-key password only from the environment"
require_contains "${repo_root}/backend-idea/build.gradle.kts" 'kast.idea.signing.certificateChainFile' "IDEA plugin signing must use a file-backed public certificate chain"
require_contains "${repo_root}/backend-idea/build.gradle.kts" 'inputArchiveFile.set(signIdeaPlugin.flatMap { it.signedArchiveFile })' "Signature verification must consume the sign task output provider"
require_not_contains "${repo_root}/backend-headless/build.gradle.kts" "reinstall with kast.sh" "Headless launcher hints must not point at retired kast.sh install behavior"

require_contains "$ci_workflow" "Maven publication metadata" "CI must validate Maven publication metadata"
require_contains "$ci_workflow" 'group: ci-${{ github.event.pull_request.number || github.ref }}' "CI must cancel superseded branch or PR validation runs"
require_contains "$ci_workflow" "runtime-contracts:" "CI must isolate runtime command and bundle contracts from the static preflight"
require_contains "$ci_workflow" "Runtime command and bundle contracts" "CI must retain runtime command and bundle contract coverage"
require_block_contains "$ci_workflow" "  runtime-contracts:" "  maven-publication-contract:" "    needs: workflow-contracts" "CI runtime contracts must wait for the static workflow preflight"
require_block_contains "$ci_workflow" "  runtime-contracts:" "  maven-publication-contract:" "      - name: Test terminal command contract" "CI runtime contracts must own the terminal command contract"
require_block_contains "$ci_workflow" "  runtime-contracts:" "  maven-publication-contract:" "      - name: Test Kast Copilot plugin package" "CI runtime contracts must reuse the terminal build for the Copilot package contract"
require_block_contains "$ci_workflow" "  runtime-contracts:" "  maven-publication-contract:" "      - name: Smoke Ubuntu/Debian bundle contract" "CI runtime contracts must own the Ubuntu/Debian bundle smoke"
require_contains "$ci_workflow" "Test CI artifact ledger" "CI must test artifact ledger recording and verification"
require_contains "$ci_workflow" "Test IDEA plugin signing and immutability contract" "CI must execute the signed immutable plugin gate"
require_contains "$ci_workflow" "ci-artifact-ledger-maven-publication" "CI must upload the Maven publication validation ledger"
require_contains "$ci_workflow" "ci-artifact-ledger-rust-cli-linux-x64" "CI must upload the Rust CLI build ledger"
require_contains "$ci_build_and_test_workflow" 'ci-artifact-ledger-headless-${{ inputs.runner }}' "CI must upload headless backend build ledgers"
require_contains "$ci_workflow" "ci-artifact-ledger-idea-plugin" "CI must upload the IDEA plugin build ledger"
require_contains "$ci_workflow" "scripts/verify-ci-artifact-ledger.py verify" "CI consumers must verify producer ledgers before packaging downloaded artifacts"
require_contains "$ci_workflow" "Rust CLI" "CI must validate the in-repo Rust CLI"
require_contains "$ci_workflow" "runs-on: ubuntu-22.04" "CI Linux CLI asset must build on an Ubuntu 22.04 glibc baseline"
require_contains "$ci_workflow" "working-directory: cli-rs" "CI Rust commands must run from cli-rs"
require_contains "$ci_build_and_test_workflow" "cache-cleanup: always" "CI Gradle setup must keep persisted Gradle caches pruned"
require_block_contains "$ci_build_and_test_workflow" "      - uses: gradle/actions/setup-gradle@v5" "      - name: Cache IntelliJ runtime distributions" 'GRADLE_BUILD_ACTION_SKIP_RESTORE: ${{ runner.os == '\''macOS'\'' && '\''dependencies transforms'\'' || '\'''\'' }}' "CI macOS builds must skip dependency and transform archives that setup-gradle can only warn about partially restoring"
require_block_contains "$ci_build_and_test_workflow" "      - uses: gradle/actions/setup-gradle@v5" "      - name: Cache IntelliJ runtime distributions" '${{ runner.os == '\''macOS'\'' && '\''caches/modules-*'\'' || '\'''\'' }}' "CI macOS builds must exclude dependency caches before setup-gradle saves shared entries"
require_block_contains "$ci_build_and_test_workflow" "      - uses: gradle/actions/setup-gradle@v5" "      - name: Cache IntelliJ runtime distributions" '${{ runner.os == '\''macOS'\'' && '\''caches/transforms-*'\'' || '\'''\'' }}' "CI macOS builds must exclude legacy transform caches before setup-gradle saves shared entries"
require_block_contains "$ci_build_and_test_workflow" "      - uses: gradle/actions/setup-gradle@v5" "      - name: Cache IntelliJ runtime distributions" '${{ runner.os == '\''macOS'\'' && '\''caches/*/transforms'\'' || '\'''\'' }}' "CI macOS builds must exclude versioned transform caches before setup-gradle saves shared entries"
require_not_contains "$ci_build_and_test_workflow" "cache-disabled: true" "CI must retain useful Gradle caches while isolating unsafe macOS shared archives"
require_contains "$ci_build_and_test_workflow" "~/.gradle/kast/headless-idea-distributions" "CI must cache the actual headless IntelliJ extraction directory"
require_contains "$ci_build_and_test_workflow" "~/.gradle/kast/shared-idea-distributions" "CI must cache the actual shared IntelliJ extraction directory"
require_contains "$ci_build_and_test_workflow" "~/.gradle/kast/backend-idea-distributions" "CI must cache the actual IDEA backend extraction directory"
require_contains "$ci_workflow" "~/.cache/pluginVerifier/ides" "CI must cache plugin verifier IDE downloads"
require_contains "$ci_build_and_test_workflow" 'intellij-runtime-${{ runner.os }}-' "CI runtime cache keys must be OS-scoped"
require_contains "$ci_workflow" 'idea-plugin-inputs-${{ runner.os }}-' "CI IDEA plugin cache keys must be OS-scoped"
require_not_contains "$ci_build_and_test_workflow" "~/.gradle/kast/idea-distributions" "CI must not cache stale unused IntelliJ extraction paths"
require_contains "$ci_workflow" "packaging/homebrew/scripts/test-formulas.py" "CI must validate Homebrew package templates"
require_contains "$ci_workflow" "Download Rust CLI CI asset" "CI bundle tests must consume a locally built CLI artifact"
require_contains "$ci_workflow" 'KAST_UBUNTU_DEBIAN_CI_BUNDLE_TAG=%s\n' "CI bundle tests must set an explicit ready-compatible bundle version"
require_contains "$ci_workflow" "v0.7.11-ci" "CI bundle tests must use a bundle version at or above the embedded backend minimum"
require_contains "$ci_workflow" 'bundle_asset="dist/kast-ubuntu-debian-headless-x86_64-${KAST_UBUNTU_DEBIAN_CI_BUNDLE_TAG}.tar.gz"' "CI bundle tests must name the bundle from the ready-compatible bundle version"
require_contains "$ci_workflow" '--version "$KAST_UBUNTU_DEBIAN_CI_BUNDLE_TAG"' "CI bundle tests must write the ready-compatible version into the bundle manifest"
require_not_contains "$ci_workflow" '--version "$KAST_RUST_CLI_TAG"' "CI bundle tests must not write the synthetic Rust CLI tag into the backend manifest"
require_contains "$ci_workflow" '"$packager_bin"' "CI bundle tests must execute the verified CLI artifact"
require_not_contains "$ci_workflow" "cargo run --manifest-path cli-rs/Cargo.toml --bin kast --locked --" "CI bundle tests must not rebuild the CLI to invoke the packager"
require_contains "$ubuntu_debian_validator" "docker pull --platform linux/amd64" "Ubuntu/Debian container validation must pre-pull matrix images with retry before docker run"
require_contains "$ci_workflow" "Test headless runtime packagers" "CI must test headless runtime and Gradle cache packagers"
require_not_contains "$ci_workflow" "npm --prefix setup-kast" "CI must not build a deleted in-repo setup-kast action"
require_contains "$ci_workflow" "Ensure zstd is available" "CI workflow contracts must install zstd before zstd-dependent local tests"
require_contains "$ci_workflow" "kast-action runtime contract" "CI must install and start the real kast-action runtime contract"
require_contains "$ci_workflow" "Package kast-action runtime inputs" "CI must package kast-action inputs from real Linux artifacts"
require_contains "$ci_workflow" "uses: amichne/kast-action@v2" "CI must invoke the published kast-action v2 line"
require_not_contains "$ci_workflow" "amichne/kast-action@v1" "CI must not invoke the old kast-action v1 line"
require_not_contains "$ci_workflow" "uses: ./setup-kast" "CI must not invoke a deleted local setup-kast action"
require_contains "$ci_workflow" "Free disk for kast-action runtime installation" "CI must reclaim unused runner SDKs before restoring runtime-contract caches"
require_block_order "$ci_workflow" "  kast-action-runtime-contract:" "  analysis-server-transport:" "Free disk for kast-action runtime installation" "uses: gradle/actions/setup-gradle@v5" "CI must reclaim unused runner SDKs before restoring runtime-contract caches"
require_block_order "$ci_workflow" "  kast-action-runtime-contract:" "  analysis-server-transport:" "Free disk for kast-action runtime installation" "Package kast-action runtime inputs" "CI must establish the runtime installation disk budget before packaging"
require_contains "$ci_workflow" "Reclaim verified runtime packaging inputs" "CI must release verified producer inputs before kast-action expands the runtime"
require_order "$ci_workflow" "Package kast-action runtime inputs" "Reclaim verified runtime packaging inputs" "CI must verify and package producer artifacts before reclaiming them"
require_order "$ci_workflow" "Reclaim verified runtime packaging inputs" "Install packaged runtime with kast-action" "CI must reclaim runtime packaging inputs before kast-action extraction"
require_block_contains "$ci_workflow" "      - name: Reclaim verified runtime packaging inputs" "      - name: Install packaged runtime with kast-action" 'rm -f "$backend_asset" "$cli_asset"' "CI must reclaim only the consumed producer archives"
require_block_contains "$ci_workflow" "      - name: Reclaim verified runtime packaging inputs" "      - name: Install packaged runtime with kast-action" '"$GRADLE_USER_HOME"/caches/transforms-*' "CI must release rebuildable Gradle transforms before runtime extraction"
require_block_contains "$ci_workflow" "      - name: Reclaim verified runtime packaging inputs" "      - name: Install packaged runtime with kast-action" "df -h /" "CI runtime disk reclamation must report the resulting disk budget"
require_block_not_contains "$ci_workflow" "      - name: Reclaim verified runtime packaging inputs" "      - name: Install packaged runtime with kast-action" '"$GRADLE_USER_HOME"/caches/modules-' "CI must retain Gradle dependency artifacts for the installed-runtime warm check"
require_contains "$ci_workflow" "scripts/verify-setup-kast-install.sh" "CI must run the shared setup-kast install verifier"
require_contains "$ci_workflow" "--workspace-id kast-action-ci-smoke" "CI kast-action verifier must use an explicit workspace id"
require_contains "$ci_workflow" '--gradle-root "$GITHUB_WORKSPACE"' "CI kast-action verifier must run a repo-level Gradle warm step after installation"
require_contains "$ci_workflow" "Test CI Gradle retry helper" "CI must test the Gradle retry helper before using it"
require_contains "$ci_workflow" "./scripts/ci-gradle-retry.sh" "CI Gradle steps must use retry helper for transient repository failures"
require_contains "$ci_workflow" "Free disk for IDEA plugin verification" "CI IDEA plugin verification must free unused runner SDKs before downloading IDEs"
require_contains "$ci_build_and_test_workflow" "Free disk for headless backend distribution" "CI headless backend builds must free unused runner SDKs before copying IntelliJ runtimes"
require_order "$ci_build_and_test_workflow" "Free disk for headless backend distribution" "Cache IntelliJ runtime distributions" "CI headless backend builds must free disk before restoring IntelliJ runtime caches"
require_contains "$ci_build_and_test_workflow" "-PkastHeadlessIdeaHomeProfile=agent" "CI must build the agent headless IDEA-home profile"
require_contains "$ci_build_and_test_workflow" "Assert headless distribution excludes fat jar" "CI must guard the headless no-fat-jar layout"
require_not_contains "$ci_build_and_test_workflow" "headless-dist-cache" "CI must not use a custom Actions cache for generated headless distributions"
require_not_contains "$ci_workflow" "idea-plugin-dist-cache" "CI must not use a custom Actions cache for generated IDEA plugin distributions"

require_contains "$ci_build_and_test_workflow" "workflow_call:" "CI build-and-test implementation must be reusable by independent platform jobs"
require_contains "$ci_build_and_test_workflow" 'runs-on: ${{ inputs.runner }}' "CI build-and-test implementation must use its typed runner input"
require_block_contains "$ci_workflow" "  build-and-test-linux:" "  build-and-test-macos:" "    uses: ./.github/workflows/ci-build-and-test.yml" "CI must call the reusable build-and-test workflow for Linux"
require_block_contains "$ci_workflow" "  build-and-test-linux:" "  build-and-test-macos:" "    needs: workflow-contracts" "CI Linux build-and-test must wait for the static workflow preflight"
require_block_contains "$ci_workflow" "  build-and-test-linux:" "  build-and-test-macos:" "      runner: ubuntu-latest" "CI Linux build-and-test must select the Ubuntu runner"
require_block_contains "$ci_workflow" "  build-and-test-macos:" "  install-ubuntu-debian-container:" "    uses: ./.github/workflows/ci-build-and-test.yml" "CI must call the reusable build-and-test workflow for macOS"
require_block_contains "$ci_workflow" "  build-and-test-macos:" "  install-ubuntu-debian-container:" "    needs: workflow-contracts" "CI macOS build-and-test must wait for the static workflow preflight"
require_block_contains "$ci_workflow" "  build-and-test-macos:" "  install-ubuntu-debian-container:" "      runner: macos-latest" "CI macOS build-and-test must select the macOS runner"
require_block_contains "$ci_workflow" "  install-ubuntu-debian-container:" "  kast-action-runtime-contract:" "      - build-and-test-linux" "Ubuntu/Debian install validation must wait for the Linux artifact producer"
require_block_contains "$ci_workflow" "  install-ubuntu-debian-container:" "  kast-action-runtime-contract:" "      - rust-cli" "Ubuntu/Debian install validation must wait for the Rust artifact producer"
require_block_not_contains "$ci_workflow" "  install-ubuntu-debian-container:" "  kast-action-runtime-contract:" "      - build-and-test-macos" "Ubuntu/Debian install validation must not wait for unrelated macOS validation"
require_block_contains "$ci_workflow" "  kast-action-runtime-contract:" "  analysis-server-transport:" "      - build-and-test-linux" "kast-action validation must wait for the Linux artifact producer"
require_block_contains "$ci_workflow" "  kast-action-runtime-contract:" "  analysis-server-transport:" "      - rust-cli" "kast-action validation must wait for the Rust artifact producer"
require_block_not_contains "$ci_workflow" "  kast-action-runtime-contract:" "  analysis-server-transport:" "      - build-and-test-macos" "kast-action validation must not wait for unrelated macOS validation"

require_contains "$snapshot_workflow" "Publish Snapshot" "Snapshot workflow must exist"
require_contains "$snapshot_workflow" "workflow_run:" "Snapshot publication must run after CI instead of racing main push builds"
require_contains "$snapshot_workflow" "- CI" "Snapshot workflow_run trigger must consume CI results"
require_not_contains "$snapshot_workflow" "pull_request:" "Snapshot publication must not start a no-op workflow for pull requests"
require_not_contains "$snapshot_workflow" "github.event.pull_request.head.sha" "Snapshot concurrency must be scoped only to publication events"
require_contains "$snapshot_workflow" "Set up Java for manual snapshot validation" "Snapshot validation must install Java only for manual publication"
require_contains "$snapshot_workflow" "Set up Gradle for manual snapshot validation" "Snapshot validation must initialize Gradle only for manual publication"
require_block_contains "$snapshot_workflow" "      - name: Set up Java for manual snapshot validation" "      - name: Set up Gradle for manual snapshot validation" "        if: github.event_name == 'workflow_dispatch'" "Snapshot Java setup must remain manual-only"
require_block_contains "$snapshot_workflow" "      - name: Set up Gradle for manual snapshot validation" "      - name: Resolve snapshot version" "        if: github.event_name == 'workflow_dispatch'" "Snapshot Gradle setup must remain manual-only"
require_contains "$snapshot_workflow" "Download CI Maven publication ledger" "Snapshot publication must consume CI's Maven validation ledger"
require_contains "$snapshot_workflow" "snapshot-artifact-ledger-maven-publication" "Snapshot publish jobs must consume a validation ledger artifact"
require_contains "$snapshot_workflow" "Verify snapshot publication ledger" "Snapshot publish jobs must verify Maven validation ledgers"
require_contains "$snapshot_workflow" "publishAllPublicationsToGitHubPackagesRepository" "Snapshot workflow must publish GitHub Packages snapshots"
require_contains "$snapshot_workflow" "publishToMavenCentral" "Snapshot workflow must publish Maven Central snapshots"
require_contains "$snapshot_workflow" "-Pkast.publish.target=snapshot" "Snapshot workflow must use the snapshot publish target"
require_not_contains "$snapshot_workflow" 'elif [[ "${GITHUB_EVENT_NAME}" == "push"' "Snapshot workflow must not publish directly from a raw push event"
require_not_contains "$snapshot_workflow" "Check GitHub Packages signing secrets" "Snapshot GitHub Packages publishing must not be gated on Maven signing secrets"
require_not_contains "$snapshot_workflow" "publishAllPublicationsToGitHubPackagesRepository \"\${signing_args[@]}\"" "Snapshot GitHub Packages publishing must not pass Maven signing credentials"
require_not_contains "$snapshot_workflow" "gh release" "Snapshot workflow must not create GitHub releases"
require_not_contains "$snapshot_workflow" "homebrew" "Snapshot workflow must not update Homebrew"

require_contains "$seed_gradle_ro_cache_workflow" "Seed Gradle read-only dependency cache" "Gradle cache seed workflow must exist"
require_contains "$seed_gradle_ro_cache_workflow" "cache-disabled: true" "Gradle cache seeding must use a clean Gradle user home"
require_contains "$seed_gradle_ro_cache_workflow" "scripts/package-gradle-ro-cache.sh" "Gradle cache seed workflow must package modules-2 through the checked-in packager"
require_contains "$seed_gradle_ro_cache_workflow" "gradle-ro-dep-cache.tar.zst" "Gradle cache seed workflow must upload the read-only cache tarball"
require_contains "$seed_gradle_ro_cache_workflow" "gradle-ro-dep-cache.sha256" "Gradle cache seed workflow must upload the read-only cache checksum"
require_contains "$seed_gradle_ro_cache_workflow" "(cd dist && sha256sum -c gradle-ro-dep-cache.sha256)" "Gradle cache seed workflow must verify sidecars from the artifact directory"
require_contains "$seed_gradle_ro_cache_workflow" "Ensure zstd is available" "Gradle cache seed workflow must install zstd when the runner image lacks it"

require_contains "$release_workflow" "Validate JVM and Maven publications" "Release must validate JVM and Maven publications"
require_contains "$release_workflow" "release-artifact-ledger-maven-publication" "Release must pass Maven validation by ledger artifact"
require_contains "$release_workflow" "Verify release Maven validation ledger" "Release Maven publication must verify the validation ledger before publishing"
require_contains "$release_workflow" "Build OpenAPI spec" "Release must build the generated OpenAPI artifact"
require_contains "$release_workflow" "stageOpenApiSpec" "Release must stage OpenAPI from the generated protocol source"
require_contains "$release_workflow" "dist/openapi.yaml" "Release must publish the OpenAPI YAML asset"
require_contains "$release_workflow" "build-provenance-openapi.json" "Release must produce OpenAPI provenance"
require_contains "$release_workflow" "build-ledger-openapi.json" "Release must produce an OpenAPI build ledger"
require_contains "$release_workflow" "openapi-spec-" "Release must upload the OpenAPI workflow artifact for provenance assembly"
require_contains "$release_workflow" '- "v*.*.*"' "Release workflow tag trigger must ignore setup-kast action major tags"
require_contains "$release_workflow" "Publish Maven Central" "Release must publish public modules to Maven Central"
require_contains "$release_workflow" "Maven Central already has all public modules" "Release Maven Central publishing must be idempotent"
require_contains "$release_workflow" "SIGNING_GPG_PRIVATE_KEY \\" "Release Maven Central gate must continue checking after the private key secret"
require_order "$release_workflow" "SIGNING_GPG_PRIVATE_KEY \\" "SIGNING_GPG_PASSPHRASE" "Release Maven Central gate must require the GPG passphrase secret before signing"
require_contains "$release_workflow" "Build Rust CLI asset" "Release must build CLI assets from cli-rs"
require_contains "$release_workflow" "build-ledger-cli-\${{ matrix.asset_id }}.json" "Release CLI builds must emit per-platform ledgers"
require_contains "$release_workflow" "working-directory: cli-rs" "Release CLI build must run from cli-rs"
require_contains "$release_workflow" "Render and push Homebrew tap" "Release must render and push the Homebrew tap"
require_contains "$release_workflow" "packaging/homebrew/scripts/update-formulas.py" "Release must use the monorepo Homebrew renderer"
require_contains "$release_workflow" "gh repo clone amichne/homebrew-kast" "Release must push the generated Homebrew tap mirror"
require_contains "$release_workflow" "git -C homebrew-tap remote set-url origin" "Release must authenticate the cloned Homebrew tap before pushing"
require_order "$release_workflow" "gh repo clone amichne/homebrew-kast" "git -C homebrew-tap remote set-url origin" "Release must authenticate the cloned Homebrew tap immediately after cloning"
require_order "$release_workflow" "git -C homebrew-tap remote set-url origin" "git -C homebrew-tap push" "Release must authenticate the Homebrew tap before pushing"
require_contains "$release_workflow" "rm -rf homebrew-tap/.github/scripts" "Release must prune retired tap-side renderer scripts"
require_contains "$release_workflow" "rm -f homebrew-tap/.github/workflows/publish-aligned-release.yml" "Release must prune retired tap-side release orchestration"
require_contains "$release_workflow" "rm -f homebrew-tap/.github/workflows/update-formula.yml" "Release must prune retired tap-side dispatch updates"
require_contains "$release_workflow" "status --porcelain --untracked-files=all" "Release Homebrew tap update must detect optional path deletions without pathspec false positives"
require_contains "$release_workflow" "git -C homebrew-tap add -A" "Release Homebrew tap update must stage optional path deletions without required pathspecs"
require_not_contains "$release_workflow" "git -C homebrew-tap add -A \\" "Release Homebrew tap update must not require optional tap paths during staging"
require_contains "$release_workflow" "Generate and upload SHA256SUMS" "Release must publish aggregate checksums"
require_contains "$release_workflow" "scripts/assemble-release-provenance.py" "Release must assemble provenance"
require_contains "$release_workflow" "scripts/verify-release-assets.sh" "Release must verify assets before publishing checksums"
require_contains "$release_workflow" "Verify published release state" "Release must have a final published-state verification job"
require_contains "$release_workflow" "scripts/verify-release-state.sh" "Release must verify the final published state"
require_contains "$release_workflow" "scripts/verify-maven-central.sh" "Release must verify Maven Central coordinates"
require_contains "$release_workflow" "./scripts/ci-gradle-retry.sh" "Release Gradle steps must use retry helper for transient repository failures"
require_contains "$release_workflow" "Free disk for IDEA plugin build" "Release IDEA plugin build must free unused runner SDKs before downloading IDEs"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'IDEA_PLUGIN_CERTIFICATE_CHAIN: ${{ secrets.IDEA_PLUGIN_CERTIFICATE_CHAIN }}' "Release preflight must require the IDEA signing certificate"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'IDEA_PLUGIN_PRIVATE_KEY: ${{ secrets.IDEA_PLUGIN_PRIVATE_KEY }}' "Release preflight must require the IDEA signing key"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'IDEA_PLUGIN_PRIVATE_KEY_PASSWORD: ${{ secrets.IDEA_PLUGIN_PRIVATE_KEY_PASSWORD }}' "Release preflight must require the IDEA signing password"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'IDEA_PLUGIN_SIGNER_SHA256: ${{ vars.IDEA_PLUGIN_SIGNER_SHA256 }}' "Release preflight must require the enrolled signer fingerprint"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ":backend-idea:verifyPlugin" "Release must verify IDEA compatibility"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ":backend-idea:signPlugin" "Release must sign the IDEA plugin"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ":backend-idea:verifyPluginSignature" "Release must verify the IDEA plugin signature"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ":backend-idea:stageIdeaPluginSignatureVerifier" "Release must stage the JetBrains verifier used for the published bytes"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "scripts/verify-idea-plugin-artifact.py record" "Release must record signer-bound IDEA provenance"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ".github/scripts/upload-immutable-release-asset.sh" "Release must upload the IDEA plugin immutably"
# shellcheck disable=SC2016 # Release shell expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" 'tag_sha="$(git rev-list -n1 "$tag")"' "Release must resolve the checked-out tag target"
# shellcheck disable=SC2016 # Release shell expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" '--signature-verifier-jar "$signature_verifier_jar"' "Published-byte verification must execute the staged JetBrains verifier"
# shellcheck disable=SC2016 # Release shell expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" '--release-tag "$tag"' "IDEA provenance must use the prepared release tag"
# shellcheck disable=SC2016 # Release shell expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" '--release-sha "$release_sha"' "IDEA provenance must use the checked-out release commit"
require_block_order "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "      - name: Build and verify IDEA plugin" "      - name: Sign and verify IDEA plugin" "Release must verify plugin structure and compatibility before signing"
require_block_order "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "      - name: Sign and verify IDEA plugin" "      - name: Stage and upload immutable signed IDEA plugin asset" "Release must verify the signature before publishing the plugin"
require_block_not_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "--clobber" "Release must never replace the signed IDEA plugin asset"
require_not_contains "$release_workflow" 'gh release upload "$tag" dist/build-provenance.json --clobber' "Release must never replace combined provenance"
require_not_contains "$release_workflow" 'gh release upload "$tag" SHA256SUMS --clobber' "Release must never replace release checksums"
require_not_contains "$release_workflow" "--clobber" "Release must never replace any existing release asset"
require_contains "$release_workflow" "Free disk for headless backend release" "Release headless backend build must free unused runner SDKs before copying IntelliJ runtimes"
require_contains "$release_workflow" "~/.gradle/kast/shared-idea-distributions" "Release must cache the actual shared IntelliJ extraction directory"
require_contains "$release_workflow" "~/.gradle/kast/backend-idea-distributions" "Release must cache the actual IDEA backend extraction directory"
require_contains "$release_workflow" "~/.gradle/kast/headless-idea-distributions" "Release must cache the actual headless IntelliJ extraction directory"
require_contains "$release_workflow" 'release-idea-plugin-inputs-${{ runner.os }}-' "Release IDEA plugin cache keys must be OS-scoped"
require_contains "$release_workflow" 'release-headless-runtime-${{ runner.os }}-' "Release headless cache keys must be OS-scoped"
require_not_contains "$release_workflow" "~/.gradle/kast/idea-distributions" "Release must not cache stale unused IntelliJ extraction paths"
require_order "$release_workflow" "Free disk for IDEA plugin build" "Cache IDEA plugin build inputs" "Release IDEA plugin build must free disk before restoring IntelliJ runtime caches"
require_order "$release_workflow" "Free disk for headless backend release" "Cache headless backend runtime inputs" "Release headless backend build must free disk before restoring IntelliJ runtime caches"
require_contains "$publishing_conventions" "publishTarget != PublishTarget.Github" "Publishing convention must not require Maven signatures for GitHub Packages"
require_contains "$release_workflow" "needs.validate-jvm.result == 'success'" "Release publication must require local JVM and Maven validation"
require_contains "$release_workflow" "needs.build-openapi-spec.result == 'success'" "Release publication must require the OpenAPI artifact"
require_contains "$release_workflow" "needs.publish-release.result" "Final release verification must read the publish-release result"
require_contains "$release_workflow" "Publish release finished with result" "Final release verification must fail when publication did not complete"
require_not_contains "$release_workflow" "publish-setup-kast-action:" "Kast releases must not publish the separate kast-action tag"
require_not_contains "$release_workflow" 'action_tag="v1"' "Kast releases must not own the stable setup action tag"
require_not_contains "$release_workflow" "needs.publish-setup-kast-action.result" "Final release verification must not depend on the separate action repo"
require_not_contains "$release_workflow" "needs.publish-maven-central.result == 'success' && needs.build-cli" "GitHub release publication must not depend on raw Maven Central job success"
require_contains "$release_workflow" "build-linux-headless-tarball:" "Default release must build the Linux headless tarball"
require_contains "$release_workflow" "Download Linux CLI release artifact" "Default release must consume the CLI artifact produced by build-cli"
require_contains "$release_workflow" 'name: rust-cli-linux-x64-${{ github.run_id }}' "Linux headless tarball packaging must use the build-cli artifact"
require_contains "$release_workflow" "release-assets/build-ledger-cli-linux-x64.json" "Linux headless tarball packaging must verify the CLI build ledger"
require_contains "$release_workflow" '"$packager_bin"' "Linux headless tarball packaging must execute the verified CLI artifact"
require_not_contains "$release_workflow" 'gh release download "$tag" --dir release-assets --pattern "kast-${tag}-linux-x64.zip"' "Linux headless tarball packaging must not consume the unledgered uploaded release asset"
require_not_contains "$release_workflow" "cargo run --manifest-path cli-rs/Cargo.toml --bin kast --locked --" "Linux headless tarball packaging must not rebuild the CLI to invoke the packager"
require_contains "$release_workflow" "package ubuntu-debian-bundle" "Default release must package the Linux headless tarball through the Rust packager"
require_contains "$release_workflow" "scripts/package-headless-runtime.sh" "Default release must package the headless runtime"
require_contains "$release_workflow" "Ensure zstd is available" "Release workflow must install zstd when the runner image lacks it"
require_contains "$release_workflow" "Package Gradle read-only dependency cache" "Release workflow must package the Gradle read-only cache from the release-SHA Gradle home"
require_contains "$release_workflow" 'cache_dir="dist/gradle-ro-cache"' "Release workflow must keep the release-SHA Gradle cache in the headless backend artifact"
require_contains "$release_workflow" 'gradle_user_home="$RUNNER_TEMP/gradle-ro-release-seed"' "Release workflow must seed the release Gradle cache in an isolated Gradle user home"
require_contains "$release_workflow" 'GRADLE_USER_HOME="$gradle_user_home" ./scripts/ci-gradle-retry.sh ./gradlew dependencies --no-daemon' "Release workflow must warm dependency metadata for the Gradle read-only cache"
require_contains "$release_workflow" 'GRADLE_USER_HOME="$gradle_user_home" ./scripts/ci-gradle-retry.sh ./gradlew buildEnvironment --no-daemon' "Release workflow must warm buildscript metadata for the Gradle read-only cache"
require_contains "$release_workflow" "2147483647" "Release workflow must reject Gradle cache assets above the GitHub release asset size limit"
require_contains "$release_workflow" "build-provenance-gradle-ro-cache.json" "Release workflow must produce provenance for the Gradle read-only cache"
require_contains "$release_workflow" "build-ledger-headless-backend.json" "Release workflow must ledger the reusable headless backend artifact"
require_contains "$release_workflow" "build-ledger-gradle-ro-cache.json" "Release workflow must ledger the Gradle read-only cache artifact"
require_contains "$release_workflow" "Verify release build ledgers" "Release publication must verify build ledgers before publishing checksums"
require_contains "$release_workflow" "Upload Gradle read-only cache release asset" "Release publication must promote the packaged Gradle read-only cache"
require_contains "$release_workflow" "provenance-linux-headless/gradle-ro-dep-cache.tar.zst" "Release publication must upload the exact Gradle cache artifact from workflow artifacts"
require_contains "$release_workflow" "kast-headless-linux-x64.tar.zst" "Default release must publish the headless runtime tarball"
require_contains "$release_workflow" "kast-headless-linux-x64.sha256" "Default release must publish the headless runtime checksum"
require_contains "$release_workflow" "(cd dist && sha256sum -c kast-headless-linux-x64.sha256)" "Release workflow must verify runtime sidecars from the artifact directory"
require_contains "$release_workflow" "kast-runtime-manifest.json" "Default release must publish the runtime manifest sidecar"
require_contains "$release_workflow" "openapi.yaml" "Default release must publish the generated OpenAPI YAML"
require_contains "$release_workflow" "gradle-ro-dep-cache.tar.zst" "Default release must publish the Gradle read-only cache tarball"
require_contains "$release_workflow" "gradle-ro-dep-cache.sha256" "Default release must publish the Gradle read-only cache checksum"
require_contains "$release_workflow" "scripts/validate-ubuntu-debian-bundle-in-docker.sh" "Default release must validate the Linux headless tarball"
require_contains "$release_workflow" "provenance-linux-headless" "Default release provenance must include the Linux headless tarball"
require_contains "$release_workflow" "headless-linux-x64" "Default release provenance must include the headless runtime tarball"
require_contains "$release_workflow" "runtime-manifest" "Default release provenance must include the runtime manifest sidecar"
require_contains "$release_workflow" "gradle-ro-cache" "Default release provenance must include the Gradle read-only cache"
require_contains "$release_workflow" "release-ubuntu-debian-headless-x86_64" "Default release ledgers must include the Linux headless tarball"
require_contains "$release_workflow" "release-headless-linux-x64" "Default release ledgers must include the headless runtime tarball"
require_contains "$release_workflow" "release-runtime-manifest" "Default release ledgers must include the runtime manifest"
require_contains "$release_workflow" "needs.build-linux-headless-tarball.result == 'success'" "Release publication must require Linux headless tarball packaging"
require_not_contains "$release_workflow" 'kast-headless-${tag}.zip' "Release must not publish a standalone headless backend zip"
require_not_contains "$release_workflow" "Upload headless backend asset" "Release must not expose a standalone headless backend asset"
require_not_contains "$release_workflow" "provenance-headless-backend" "Release provenance must not include a standalone headless backend asset"
require_not_contains "$release_workflow" "Dispatch Homebrew tap update" "Release must render the tap directly instead of dispatching component updates"
require_not_contains "$kast_script" "cmd_install_retired" "Repo build helper must not carry a retired install command"
require_not_contains "$kast_script" "The kast.sh shell installer is retired" "Repo build helper must not preserve retired installer messaging"
require_order "$release_workflow" "Package Gradle read-only dependency cache" "Upload headless backend artifact" "Release must package the Gradle cache before uploading the reusable workflow artifact"
require_order "$release_workflow" "Upload Gradle read-only cache release asset" "Generate and upload SHA256SUMS" "Release must publish the Gradle cache before checksumming the release bundle"
require_order "$release_workflow" "Generate and upload SHA256SUMS" "Publish draft release with provenance annotation" "Release must verify checksums before publication"
require_order "$release_workflow" "Publish draft release with provenance annotation" "Render and push Homebrew tap" "Release must publish assets before updating Homebrew"
require_order "$release_workflow" "Render and push Homebrew tap" "verify-release-state:" "Final verification must run after the Homebrew publication path"

require_contains "$release_provenance_assembler" '"cli-linux-x64"' "Release provenance must include Linux x64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-linux-arm64"' "Release provenance must include Linux arm64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-macos-x64"' "Release provenance must include macOS x64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-macos-arm64"' "Release provenance must include macOS arm64 CLI assets"
require_contains "$release_provenance_assembler" '"gradle-ro-cache"' "Release provenance must include the Gradle read-only cache"
require_contains "$release_provenance_assembler" '"headless-linux-x64"' "Release provenance must include the headless runtime tarball"
require_contains "$release_provenance_assembler" '"runtime-manifest"' "Release provenance must include the runtime manifest"
require_contains "$release_provenance_assembler" '"ubuntu-debian-headless-x86_64"' "Release provenance must include the Linux headless tarball"
require_not_contains "$release_provenance_assembler" '"headless"' "Release provenance must not include a standalone headless backend asset"
require_contains "$ci_artifact_ledger_verifier" "schemaVersion" "CI artifact ledger verifier must enforce schema versions"
require_contains "$ci_artifact_ledger_verifier" "duplicate artifactKind" "CI artifact ledger verifier must reject duplicate artifact kinds"
require_contains "$ci_artifact_ledger_verifier" "sha256 mismatch" "CI artifact ledger verifier must reject digest drift"
require_contains "$ci_artifact_ledger_test" "tampered artifact unexpectedly verified" "CI artifact ledger test must cover digest drift"
require_contains "$ci_artifact_ledger_test" "duplicate artifact kind unexpectedly verified" "CI artifact ledger test must cover duplicate artifact kinds"
require_contains "$release_asset_verifier" '"cli-linux-x64"' "Release verifier must require CLI assets"
require_contains "$release_asset_verifier" 'kast-{tag}-macos-arm64.zip' "Release verifier must require macOS CLI assets"
require_contains "$release_asset_verifier" 'gradle-ro-dep-cache.tar.zst' "Release verifier must require the Gradle read-only cache tarball"
require_contains "$release_asset_verifier" 'kast-headless-linux-x64.tar.zst' "Release verifier must require the headless runtime tarball"
require_contains "$release_asset_verifier" 'kast-runtime-manifest.json' "Release verifier must require the runtime manifest"
require_contains "$release_asset_verifier" 'openapi.yaml' "Release verifier must require the OpenAPI artifact"
require_contains "$release_asset_verifier" 'kast-ubuntu-debian-headless-x86_64-{tag}.tar.gz' "Release verifier must require the Linux headless tarball"
require_not_contains "$release_asset_verifier" 'kast-headless-{tag}.zip' "Release verifier must not accept a standalone headless backend asset"
require_contains "$release_state_verifier" "gh release download" "Release state verifier must download release assets"
require_contains "$release_state_verifier" "scripts/verify-release-assets.sh" "Release state verifier must reuse the asset verifier"
require_contains "$release_state_verifier" "scripts/verify-maven-central.sh" "Release state verifier must verify Maven Central"
require_contains "$release_state_verifier" "releases/latest" "Release state verifier must prove stable releases are latest"
require_not_contains "$release_state_verifier" 'tag_commit_sha "v1"' "Release state verifier must not inspect the separate setup action tag"
require_not_contains "$release_state_verifier" 'setup-kast/action.yml' "Release state verifier must not require in-repo setup action metadata"
require_not_contains "$release_state_verifier" 'setup-kast/dist/index.js' "Release state verifier must not require in-repo setup action dist"
require_contains "$release_state_verifier" "homebrew-kast" "Release state verifier must prove stable Homebrew state"
require_contains "$release_state_verifier" "referenced_cli_assets" "Release state verifier must derive formula assets from the rendered tap"
require_not_contains "$release_state_verifier" "formula_assets = [" "Release state verifier must not require release assets that the rendered formula does not reference"
require_contains "$maven_central_verifier" "kast-analysis-api" "Maven Central verifier must check analysis-api"
require_contains "$maven_central_verifier" "kast-analysis-server" "Maven Central verifier must check analysis-server"
require_contains "$maven_central_verifier" "kast-index-store" "Maven Central verifier must check index-store"
require_contains "$ubuntu_debian_validator" "--accept-indexing=true" "Ubuntu/Debian validator must accept servable indexing state during cold startup"
require_contains "$ubuntu_debian_validator" 'kast developer runtime capabilities "${backend_args[@]}" --workspace-root="${KAST_UBUNTU_DEBIAN_SMOKE_WORKSPACE}" --accept-indexing=true --no-auto-start=true' "Ubuntu/Debian validator capabilities smoke must accept servable indexing state"
require_contains "$headless_runtime_packager" "kast-runtime-manifest.json" "Headless runtime packager must emit a runtime manifest"
require_contains "$headless_runtime_packager" "artifactSha256" "Headless runtime packager must bind the manifest to the tarball digest"
require_contains "$gradle_ro_cache_packager" "modules-2" "Gradle RO cache packager must package modules-2"
require_contains "$gradle_ro_cache_packager" "gc.properties" "Gradle RO cache packager must exclude Gradle GC metadata"
require_contains "$setup_kast_verifier" "up_args=(" "setup-kast verifier must start the installed headless runtime"
require_contains "$setup_kast_verifier" "capabilities \\" "setup-kast verifier must prove the installed runtime is reachable"
require_contains "$setup_kast_verifier" "Kast install directory contains daemon state" "setup-kast verifier must prove daemon state stays out of the immutable install tree"
require_contains "$setup_kast_verifier" "kast on PATH does not match install-dir" "setup-kast verifier must reject stale kast binaries earlier on PATH"
require_contains "$setup_kast_verifier" "GRADLE_USER_HOME is unset" "setup-kast verifier must require writable Gradle session state"
require_contains "$setup_kast_verifier" "read-only tree has writable entries" "setup-kast verifier must reject mutable Gradle RO cache entries"
require_contains "$setup_kast_verifier" "run_gradle_warm_command" "setup-kast verifier must own repo-level Gradle warm checks"
require_contains "$setup_kast_verifier" "dependencies --no-daemon" "setup-kast verifier must run the Gradle dependencies warm task"
require_contains "$setup_kast_verifier" "buildEnvironment --no-daemon" "setup-kast verifier must run the Gradle buildEnvironment warm task"
require_contains "$release_and_mirror_doc" "kast developer release package ubuntu-debian-bundle" "Release workflow docs must document bundle packaging"
require_contains "$release_and_mirror_doc" "kast developer release activate bundle" "Release workflow docs must document bundle activation"
require_contains "$release_and_mirror_doc" "scripts/install-ubuntu-debian.sh" "Release workflow docs must document the server installer"
require_contains "$release_and_mirror_doc" "scripts/verify-release-assets.sh" "Release workflow docs must document release asset verification"
require_contains "$runtime_artifact_contract" "scripts/verify-ci-artifact-ledger.py verify" "Runtime artifact docs must document build receipt verification"
require_not_contains "$runtime_artifact_contract" "setup-kast action" "Runtime artifact docs must not publish setup-kast as the action name"
require_not_contains "$runtime_artifact_contract" "amichne/kast-action@v1" "Runtime artifact docs must not document the old action line"
require_not_contains "$runtime_artifact_contract" "Copilot Setup Steps" "Runtime artifact docs must not document obsolete GitHub coding-agent setup"
require_contains "$kast_script" "-Pname=value" "kast.sh build help must document Gradle property forwarding"

printf '%s\n' "Release workflow contract passed"
