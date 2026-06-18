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

repo_root="$(resolve_repo_root)"
ci_workflow="${repo_root}/.github/workflows/ci.yml"
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
release_asset_verifier="${repo_root}/scripts/verify-release-assets.sh"
release_state_verifier="${repo_root}/scripts/verify-release-state.sh"
maven_central_verifier="${repo_root}/scripts/verify-maven-central.sh"
ubuntu_debian_validator="${repo_root}/scripts/validate-ubuntu-debian-bundle-in-docker.sh"
devin_runtime_packager="${repo_root}/scripts/package-devin-runtime.sh"
gradle_ro_cache_packager="${repo_root}/scripts/package-gradle-ro-cache.sh"
setup_kast_verifier="${repo_root}/scripts/verify-setup-kast-install.sh"
ci_gradle_retry="${repo_root}/scripts/ci-gradle-retry.sh"
ci_gradle_retry_test="${repo_root}/.github/scripts/test-ci-gradle-retry.sh"
devin_packager_test="${repo_root}/.github/scripts/test-devin-artifact-packagers.sh"
setup_kast_action_test="${repo_root}/.github/scripts/test-setup-kast-action.sh"
setup_kast_real_artifact_test="${repo_root}/.github/scripts/test-setup-kast-real-artifacts.sh"
setup_kast_action="${repo_root}/setup-kast/action.yml"
setup_kast_source="${repo_root}/setup-kast/src/index.ts"
runtime_artifact_contract="${repo_root}/docs/distribution/runtime-artifact-contract.md"
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
  "$release_asset_verifier" \
  "$release_state_verifier" \
  "$maven_central_verifier" \
  "$ubuntu_debian_validator" \
  "$devin_runtime_packager" \
  "$gradle_ro_cache_packager" \
  "$setup_kast_verifier" \
  "$ci_gradle_retry" \
  "$ci_gradle_retry_test" \
  "$devin_packager_test" \
  "$setup_kast_action_test" \
  "$setup_kast_real_artifact_test" \
  "$setup_kast_action" \
  "$setup_kast_source" \
  "$runtime_artifact_contract" \
  "$kast_script"
do
  [[ -f "$path" || -x "$path" ]] || die "Required release file is missing: $path"
done

for workflow in "$ci_workflow" "$release_workflow" "$snapshot_workflow" "$docs_workflow" "$seed_gradle_ro_cache_workflow"; do
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

require_contains "$ci_workflow" "Maven publication metadata" "CI must validate Maven publication metadata"
require_contains "$ci_workflow" "Rust CLI" "CI must validate the in-repo Rust CLI"
require_contains "$ci_workflow" "runs-on: ubuntu-22.04" "CI Linux CLI asset must build on an Ubuntu 22.04 glibc baseline"
require_contains "$ci_workflow" "working-directory: cli-rs" "CI Rust commands must run from cli-rs"
require_contains "$ci_workflow" "cache-cleanup: always" "CI Gradle setup must keep persisted Gradle caches pruned"
require_contains "$ci_workflow" "packaging/homebrew/scripts/test-formulas.py" "CI must validate Homebrew package templates"
require_contains "$ci_workflow" "Download Rust CLI CI asset" "CI bundle tests must consume a locally built CLI artifact"
require_contains "$ci_workflow" "Test Devin artifact packagers" "CI must test Devin runtime and Gradle cache packagers"
require_contains "$ci_workflow" "Test setup-kast action" "CI must test the Devin-compatible setup action"
require_contains "$ci_workflow" "npm --prefix setup-kast test" "CI must type-check the setup-kast action source before action smoke tests"
require_contains "$ci_workflow" "Ensure zstd is available" "CI workflow contracts must install zstd before zstd-dependent local tests"
require_contains "$ci_workflow" "setup-kast runtime artifact" "CI must install and start the real setup-kast runtime artifact"
require_contains "$ci_workflow" "Package setup-kast runtime inputs" "CI must package setup-kast inputs from real Linux artifacts"
require_contains "$ci_workflow" "uses: ./setup-kast" "CI must invoke setup-kast as an actual local GitHub Action"
require_contains "$ci_workflow" "scripts/verify-setup-kast-install.sh" "CI must run the shared setup-kast install verifier"
require_contains "$ci_workflow" "--workspace-id setup-kast-ci-smoke" "CI setup-kast verifier must use an explicit workspace id"
require_contains "$ci_workflow" '--gradle-root "$GITHUB_WORKSPACE"' "CI setup-kast verifier must run a repo-level Gradle warm step after installation"
require_contains "$ci_workflow" "Test CI Gradle retry helper" "CI must test the Gradle retry helper before using it"
require_contains "$ci_workflow" "./scripts/ci-gradle-retry.sh" "CI Gradle steps must use retry helper for transient repository failures"
require_contains "$ci_workflow" "-PkastHeadlessIdeaHomeProfile=agent" "CI must build the agent headless IDEA-home profile"
require_contains "$ci_workflow" "Assert headless distribution excludes fat jar" "CI must guard the headless no-fat-jar layout"
require_not_contains "$ci_workflow" "headless-dist-cache" "CI must not use a custom Actions cache for generated headless distributions"
require_not_contains "$ci_workflow" "idea-plugin-dist-cache" "CI must not use a custom Actions cache for generated IDEA plugin distributions"
require_not_contains "$ci_workflow" "Smoke Devin headless runtime contract" "CI must not maintain a separate Devin headless runtime smoke"
require_not_contains "$ci_workflow" "devin-headless-runtime" "CI must not publish a separate Devin headless runtime artifact"

require_contains "$snapshot_workflow" "Publish Snapshot" "Snapshot workflow must exist"
require_contains "$snapshot_workflow" "publishAllPublicationsToGitHubPackagesRepository" "Snapshot workflow must publish GitHub Packages snapshots"
require_contains "$snapshot_workflow" "publishToMavenCentral" "Snapshot workflow must publish Maven Central snapshots"
require_contains "$snapshot_workflow" "-Pkast.publish.target=snapshot" "Snapshot workflow must use the snapshot publish target"
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
require_contains "$release_workflow" "Publish Maven Central" "Release must publish public modules to Maven Central"
require_contains "$release_workflow" "Maven Central already has all public modules" "Release Maven Central publishing must be idempotent"
require_contains "$release_workflow" "SIGNING_GPG_PRIVATE_KEY \\" "Release Maven Central gate must continue checking after the private key secret"
require_order "$release_workflow" "SIGNING_GPG_PRIVATE_KEY \\" "SIGNING_GPG_PASSPHRASE" "Release Maven Central gate must require the GPG passphrase secret before signing"
require_contains "$release_workflow" "Build Rust CLI asset" "Release must build CLI assets from cli-rs"
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
require_contains "$release_workflow" "needs.validate-jvm.result == 'success'" "Release publication must require local JVM and Maven validation"
require_contains "$release_workflow" "needs.publish-release.result" "Final release verification must read the publish-release result"
require_contains "$release_workflow" "Publish release finished with result" "Final release verification must fail when publication did not complete"
require_not_contains "$release_workflow" "needs.publish-maven-central.result == 'success' && needs.build-cli" "GitHub release publication must not depend on raw Maven Central job success"
require_contains "$release_workflow" "build-linux-headless-tarball:" "Default release must build the Linux headless tarball"
require_contains "$release_workflow" "scripts/package-ubuntu-debian-bundle.sh" "Default release must package the Linux headless tarball"
require_contains "$release_workflow" "scripts/package-devin-runtime.sh" "Default release must package the Devin-compatible headless runtime"
require_contains "$release_workflow" "Ensure zstd is available" "Release workflow must install zstd when the runner image lacks it"
require_contains "$release_workflow" "kast-headless-linux-x64.tar.zst" "Default release must publish the Devin-compatible runtime tarball"
require_contains "$release_workflow" "kast-headless-linux-x64.sha256" "Default release must publish the Devin-compatible runtime checksum"
require_contains "$release_workflow" "(cd dist && sha256sum -c kast-headless-linux-x64.sha256)" "Release workflow must verify runtime sidecars from the artifact directory"
require_contains "$release_workflow" "kast-runtime-manifest.json" "Default release must publish the runtime manifest sidecar"
require_contains "$release_workflow" "scripts/validate-ubuntu-debian-bundle-in-docker.sh" "Default release must validate the Linux headless tarball"
require_contains "$release_workflow" "provenance-linux-headless" "Default release provenance must include the Linux headless tarball"
require_contains "$release_workflow" "headless-linux-x64" "Default release provenance must include the Devin-compatible runtime tarball"
require_contains "$release_workflow" "runtime-manifest" "Default release provenance must include the runtime manifest sidecar"
require_contains "$release_workflow" "needs.build-linux-headless-tarball.result == 'success'" "Release publication must require Linux headless tarball packaging"
require_not_contains "$release_workflow" 'kast-headless-${tag}.zip' "Release must not publish a standalone headless backend zip"
require_not_contains "$release_workflow" "Upload headless backend asset" "Release must not expose a standalone headless backend asset"
require_not_contains "$release_workflow" "provenance-headless-backend" "Release provenance must not include a standalone headless backend asset"
require_not_contains "$release_workflow" "devin-headless" "Release must not expose a Devin-specific headless runtime"
require_not_contains "$release_workflow" "Dispatch Homebrew tap update" "Release must render the tap directly instead of dispatching component updates"
require_order "$release_workflow" "Generate and upload SHA256SUMS" "Publish draft release with provenance annotation" "Release must verify checksums before publication"
require_order "$release_workflow" "Publish draft release with provenance annotation" "Render and push Homebrew tap" "Release must publish assets before updating Homebrew"
require_order "$release_workflow" "Render and push Homebrew tap" "verify-release-state:" "Final verification must run after the Homebrew publication path"

require_contains "$release_provenance_assembler" '"cli-linux-x64"' "Release provenance must include Linux x64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-linux-arm64"' "Release provenance must include Linux arm64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-macos-x64"' "Release provenance must include macOS x64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-macos-arm64"' "Release provenance must include macOS arm64 CLI assets"
require_contains "$release_provenance_assembler" '"headless-linux-x64"' "Release provenance must include the Devin-compatible runtime tarball"
require_contains "$release_provenance_assembler" '"runtime-manifest"' "Release provenance must include the runtime manifest"
require_contains "$release_provenance_assembler" '"ubuntu-debian-headless-x86_64"' "Release provenance must include the Linux headless tarball"
require_not_contains "$release_provenance_assembler" '"headless"' "Release provenance must not include a standalone headless backend asset"
require_not_contains "$release_provenance_assembler" '"devin-headless-linux-x64"' "Release provenance must not support a separate Devin headless runtime asset"
require_contains "$release_asset_verifier" '"cli-linux-x64"' "Release verifier must require CLI assets"
require_contains "$release_asset_verifier" 'kast-{tag}-macos-arm64.zip' "Release verifier must require macOS CLI assets"
require_contains "$release_asset_verifier" 'kast-headless-linux-x64.tar.zst' "Release verifier must require the Devin-compatible runtime tarball"
require_contains "$release_asset_verifier" 'kast-runtime-manifest.json' "Release verifier must require the runtime manifest"
require_contains "$release_asset_verifier" 'kast-ubuntu-debian-headless-x86_64-{tag}.tar.gz' "Release verifier must require the Linux headless tarball"
require_not_contains "$release_asset_verifier" 'kast-headless-{tag}.zip' "Release verifier must not accept a standalone headless backend asset"
require_not_contains "$release_asset_verifier" 'kast-devin-headless-runtime-linux-x64-{tag}.tar.gz' "Release verifier must not accept a separate Devin headless runtime asset"
require_contains "$release_state_verifier" "gh release download" "Release state verifier must download release assets"
require_contains "$release_state_verifier" "scripts/verify-release-assets.sh" "Release state verifier must reuse the asset verifier"
require_contains "$release_state_verifier" "scripts/verify-maven-central.sh" "Release state verifier must verify Maven Central"
require_contains "$release_state_verifier" "releases/latest" "Release state verifier must prove stable releases are latest"
require_contains "$release_state_verifier" "homebrew-kast" "Release state verifier must prove stable Homebrew state"
require_contains "$release_state_verifier" "referenced_cli_assets" "Release state verifier must derive formula assets from the rendered tap"
require_not_contains "$release_state_verifier" "formula_assets = [" "Release state verifier must not require release assets that the rendered formula does not reference"
require_contains "$maven_central_verifier" "kast-analysis-api" "Maven Central verifier must check analysis-api"
require_contains "$maven_central_verifier" "kast-analysis-server" "Maven Central verifier must check analysis-server"
require_contains "$maven_central_verifier" "kast-index-store" "Maven Central verifier must check index-store"
require_contains "$ubuntu_debian_validator" "--accept-indexing=true" "Ubuntu/Debian validator must accept servable indexing state during cold startup"
require_contains "$ubuntu_debian_validator" 'kast capabilities "${backend_args[@]}" --workspace-root="${KAST_UBUNTU_DEBIAN_SMOKE_WORKSPACE}" --accept-indexing=true --no-auto-start=true' "Ubuntu/Debian validator capabilities smoke must accept servable indexing state"
require_contains "$devin_runtime_packager" "kast-runtime-manifest.json" "Devin runtime packager must emit a runtime manifest"
require_contains "$devin_runtime_packager" "artifactSha256" "Devin runtime packager must bind the manifest to the tarball digest"
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
require_contains "$setup_kast_action" "runs:" "setup-kast must be a GitHub Action"
require_contains "$setup_kast_action" "using: node20" "setup-kast must be a Devin-compatible Node action"
require_contains "$setup_kast_action" "path-safe version segment" "setup-kast action metadata must document version path safety"
require_contains "$setup_kast_action" "manifest-url" "setup-kast must accept the runtime manifest sidecar"
require_contains "$setup_kast_action" "authorization-header" "setup-kast must support header-based private artifact downloads"
require_contains "$setup_kast_action" "gradle-ro-cache-authorization-header" "setup-kast must support distinct Gradle cache download credentials"
require_contains "$setup_kast_action" "download-attempts" "setup-kast must expose bounded artifact download retries"
require_contains "$setup_kast_source" "Readable.fromWeb" "setup-kast must stream HTTP artifact responses"
require_contains "$setup_kast_source" "pipeline(" "setup-kast must use streaming file writes for HTTP artifacts"
require_contains "$setup_kast_source" "createReadStream" "setup-kast must stream checksum calculation for large artifacts"
require_not_contains "$setup_kast_source" "arrayBuffer()" "setup-kast must not buffer entire HTTP artifacts in memory"
require_contains "$setup_kast_action_test" "authorized download" "setup-kast action test must cover authenticated artifact downloads"
require_contains "$setup_kast_action_test" "download failure leaked signed URL query parameters" "setup-kast action test must prove signed URL secrets stay out of logs"
require_contains "$setup_kast_action_test" "missing required tool 'zstd'" "setup-kast action test must cover missing zstd preflight"
require_contains "$setup_kast_action_test" "version must be a semver path segment" "setup-kast action test must reject path-like versions"
require_contains "$setup_kast_action_test" "bad runtime shape created current symlink" "setup-kast action test must fail malformed runtime archives before installation"
require_contains "$setup_kast_action_test" "strict failing reinstall did not restore the previous current symlink" "setup-kast action test must prove strict reinstall rollback"
require_contains "$setup_kast_action_test" "strict failing same-version reinstall replaced the working runtime" "setup-kast action test must prove same-version strict reinstall rollback"
require_contains "$setup_kast_action_test" "Gradle warm verifier" "setup-kast action test must prove the verifier runs Gradle warm commands with setup-kast environment"
require_contains "$runtime_artifact_contract" "authorization-header" "Runtime artifact docs must document private artifact store credentials"
require_contains "$runtime_artifact_contract" "never prints full HTTP artifact URLs" "Runtime artifact docs must document secret-safe download diagnostics"
require_contains "$runtime_artifact_contract" "streamed to disk" "Runtime artifact docs must document large artifact memory behavior"
require_contains "$runtime_artifact_contract" "Install artifact decompression tools" "Runtime artifact docs must make zstd setup explicit for blueprints"
require_contains "$runtime_artifact_contract" 'github.com/<owner>/<repo>/<subpath>@<ref>' "Runtime artifact docs must document Devin subpath action pinning"
require_contains "$runtime_artifact_contract" "command -v kast" "Runtime artifact docs must keep blueprint validation independent of repo-local scripts"
require_contains "$runtime_artifact_contract" "test -d /opt/kast/cache/gradle-ro/modules-2" "Runtime artifact docs must validate the Gradle read-only cache in blueprint snippets"
require_contains "$runtime_artifact_contract" 'requires `tar` and `zstd`' "Runtime artifact docs must document setup-kast external decompression requirements"
require_contains "$runtime_artifact_contract" 'version` must be a semver path segment' "Runtime artifact docs must document version path safety"
require_contains "$runtime_artifact_contract" "repo-level Gradle warm" "Runtime artifact docs must document post-install Gradle warm verification"
require_contains "$setup_kast_real_artifact_test" "KAST_SETUP_KAST_SMOKE_GRADLE_WARM" "setup-kast real artifact smoke must support Gradle warm verification"
require_contains "$setup_kast_real_artifact_test" "KAST_SETUP_KAST_SMOKE_BUILD" "setup-kast real artifact smoke must support rebuilding current artifacts"
require_contains "$setup_kast_real_artifact_test" "scripts/verify-setup-kast-install.sh" "setup-kast real artifact smoke must use the shared install verifier"
require_contains "$kast_script" "-Pname=value" "kast.sh build help must document Gradle property forwarding"

printf '%s\n' "Release workflow contract passed"
