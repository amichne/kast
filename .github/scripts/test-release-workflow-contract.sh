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
kast_script="${repo_root}/kast.sh"

for path in \
  "$ci_workflow" \
  "$release_workflow" \
  "$snapshot_workflow" \
  "$docs_workflow" \
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
  "$kast_script"
do
  [[ -f "$path" || -x "$path" ]] || die "Required release file is missing: $path"
done

for workflow in "$ci_workflow" "$release_workflow" "$snapshot_workflow" "$docs_workflow"; do
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

require_contains "${repo_root}/analysis-api/build.gradle.kts" 'artifactId.set("kast-analysis-api")' "analysis-api must publish the public Maven artifact"
require_contains "${repo_root}/analysis-server/build.gradle.kts" 'artifactId.set("kast-analysis-server")' "analysis-server must publish the public Maven artifact"
require_contains "${repo_root}/index-store/build.gradle.kts" 'artifactId.set("kast-index-store")' "index-store must publish the public Maven artifact"
require_not_contains "${repo_root}/backend-standalone/build.gradle.kts" "kastPublishing" "Standalone backend must remain release-asset-only"
require_not_contains "${repo_root}/backend-headless/build.gradle.kts" "kastPublishing" "Headless backend must remain release-asset-only"
require_not_contains "${repo_root}/backend-intellij/build.gradle.kts" "kastPublishing" "IntelliJ plugin must remain release-asset-only"

require_contains "$ci_workflow" "Maven publication metadata" "CI must validate Maven publication metadata"
require_contains "$ci_workflow" "Rust CLI" "CI must validate the in-repo Rust CLI"
require_contains "$ci_workflow" "runs-on: ubuntu-22.04" "CI Linux CLI asset must build on an Ubuntu 22.04 glibc baseline"
require_contains "$ci_workflow" "working-directory: cli-rs" "CI Rust commands must run from cli-rs"
require_contains "$ci_workflow" "packaging/homebrew/scripts/test-formulas.py" "CI must validate Homebrew package templates"
require_contains "$ci_workflow" "Download Rust CLI CI asset" "CI bundle tests must consume a locally built CLI artifact"
require_not_contains "$ci_workflow" "repository: amichne/kast-rs" "CI must not checkout the retired kast-rs repo"
require_not_contains "$ci_workflow" "--repo amichne/kast-rs" "CI must not download CLI assets from the retired kast-rs repo"

require_contains "$snapshot_workflow" "Publish Snapshot" "Snapshot workflow must exist"
require_contains "$snapshot_workflow" "publishAllPublicationsToGitHubPackagesRepository" "Snapshot workflow must publish GitHub Packages snapshots"
require_contains "$snapshot_workflow" "publishToMavenCentral" "Snapshot workflow must publish Maven Central snapshots"
require_contains "$snapshot_workflow" "-Pkast.publish.target=snapshot" "Snapshot workflow must use the snapshot publish target"
require_not_contains "$snapshot_workflow" "gh release" "Snapshot workflow must not create GitHub releases"
require_not_contains "$snapshot_workflow" "homebrew" "Snapshot workflow must not update Homebrew"

require_contains "$release_workflow" "Validate JVM and Maven publications" "Release must validate JVM and Maven publications"
require_contains "$release_workflow" "Publish Maven Central" "Release must publish public modules to Maven Central"
require_contains "$release_workflow" "SIGNING_GPG_PRIVATE_KEY \\" "Release Maven Central gate must continue checking after the private key secret"
require_order "$release_workflow" "SIGNING_GPG_PRIVATE_KEY \\" "SIGNING_GPG_PASSPHRASE" "Release Maven Central gate must require the GPG passphrase secret before signing"
require_contains "$release_workflow" "Build Rust CLI asset" "Release must build CLI assets from cli-rs"
require_contains "$release_workflow" "working-directory: cli-rs" "Release CLI build must run from cli-rs"
require_contains "$release_workflow" "rust-cli-linux-x64" "Release bundles must consume the locally built Linux x64 CLI artifact"
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
require_not_contains "$release_workflow" "--repo amichne/kast-rs" "Release must not depend on kast-rs release assets"
require_not_contains "$release_workflow" "Dispatch Homebrew tap update" "Release must render the tap directly instead of dispatching component updates"
require_order "$release_workflow" "Generate and upload SHA256SUMS" "Publish draft release with provenance annotation" "Release must verify checksums before publication"
require_order "$release_workflow" "Publish draft release with provenance annotation" "Render and push Homebrew tap" "Release must publish assets before updating Homebrew"

require_contains "$release_provenance_assembler" '"cli-linux-x64"' "Release provenance must include Linux x64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-linux-arm64"' "Release provenance must include Linux arm64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-macos-x64"' "Release provenance must include macOS x64 CLI assets"
require_contains "$release_provenance_assembler" '"cli-macos-arm64"' "Release provenance must include macOS arm64 CLI assets"
require_contains "$release_asset_verifier" '"cli-linux-x64"' "Release verifier must require CLI assets"
require_contains "$release_asset_verifier" 'kast-{tag}-macos-arm64.zip' "Release verifier must require macOS CLI assets"
require_contains "$kast_script" "-Pname=value" "kast.sh build help must document Gradle property forwarding"

require_not_contains "$docs_workflow" "repository: amichne/kast-rs" "Docs workflow must use in-repo CLI command catalog"

printf '%s\n' "Release workflow contract passed"
