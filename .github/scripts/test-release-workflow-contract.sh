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
copilot_setup_workflow="${repo_root}/.github/workflows/copilot-setup-steps.yml"
docs_workflow="${repo_root}/.github/workflows/docs.yml"
release_provenance_assembler="${repo_root}/scripts/assemble-release-provenance.py"
ubuntu_debian_installer="${repo_root}/scripts/install-ubuntu-debian.sh"
ubuntu_debian_packager="${repo_root}/scripts/package-ubuntu-debian-bundle.sh"
ubuntu_debian_smoke="${repo_root}/scripts/smoke-ubuntu-debian-bundle.sh"
ubuntu_debian_docker_validator="${repo_root}/scripts/validate-ubuntu-debian-bundle-in-docker.sh"
settings_file="${repo_root}/settings.gradle.kts"
root_build_file="${repo_root}/build.gradle.kts"
kast_script="${repo_root}/kast.sh"

[[ -f "$ci_workflow" ]] || die "CI workflow not found: $ci_workflow"
[[ -f "$release_workflow" ]] || die "Release workflow not found: $release_workflow"
[[ -f "$copilot_setup_workflow" ]] || die "Copilot setup workflow not found: $copilot_setup_workflow"
[[ -f "$docs_workflow" ]] || die "Documentation workflow not found: $docs_workflow"
[[ -x "$release_provenance_assembler" ]] || die "Release provenance assembler not found or not executable: $release_provenance_assembler"
[[ -x "$ubuntu_debian_installer" ]] || die "Ubuntu/Debian installer not found or not executable: $ubuntu_debian_installer"
[[ -x "$ubuntu_debian_packager" ]] || die "Ubuntu/Debian bundle packager not found or not executable: $ubuntu_debian_packager"
[[ -x "$ubuntu_debian_smoke" ]] || die "Ubuntu/Debian bundle smoke not found or not executable: $ubuntu_debian_smoke"
[[ -x "$ubuntu_debian_docker_validator" ]] || die "Ubuntu/Debian Docker validator not found or not executable: $ubuntu_debian_docker_validator"
[[ -f "$settings_file" ]] || die "settings file not found: $settings_file"
[[ -f "$root_build_file" ]] || die "root build file not found: $root_build_file"
[[ -f "$kast_script" ]] || die "kast.sh not found: $kast_script"
[[ ! -d "${repo_root}/kast-cli" ]] || die "Old JVM CLI module still exists at ${repo_root}/kast-cli"
[[ ! -f "${repo_root}/scripts/headless-agent-install.sh" ]] || die "Old headless-agent installer must be removed"
[[ ! -f "${repo_root}/scripts/package-headless-agent-bundle.sh" ]] || die "Old headless-agent bundle packager must be removed"
[[ ! -f "${repo_root}/scripts/smoke-headless-agent-install.sh" ]] || die "Old headless-agent install smoke must be removed"
[[ ! -f "${repo_root}/scripts/smoke-headless-agent-bundle.sh" ]] || die "Old headless-agent bundle smoke must be removed"
[[ ! -f "${repo_root}/scripts/package-devin-bundle.sh" ]] || die "Devin-specific packager must be replaced by Ubuntu/Debian packager"
[[ ! -f "${repo_root}/scripts/smoke-devin-bundle.sh" ]] || die "Devin-specific smoke must be replaced by Ubuntu/Debian smoke"
[[ ! -f "${repo_root}/scripts/validate-devin-bundle-in-docker.sh" ]] || die "Devin-specific Docker validator must be replaced by Ubuntu/Debian validator"
[[ ! -f "${repo_root}/.devin/install-kast-devin.sh" ]] || die "Devin-specific installer wrapper must be removed"
[[ ! -f "${repo_root}/.devin/verify-kast-devin.sh" ]] || die "Devin-specific verifier wrapper must be removed"

for workflow in "$ci_workflow" "$release_workflow" "$copilot_setup_workflow" "$docs_workflow"; do
  require_not_contains "$workflow" "actions/cache@v4" "Workflow actions must not use the Node 20 cache action"
  require_not_contains "$workflow" "actions/upload-artifact@v4" "Workflow actions must not use the Node 20 upload-artifact action"
  require_not_contains "$workflow" "actions/upload-artifact@v5" "Workflow actions must not use the preliminary Node 24 upload-artifact action"
  require_not_contains "$workflow" "actions/download-artifact@v4" "Workflow actions must not use the Node 20 download-artifact action"
  require_not_contains "$workflow" "actions/download-artifact@v5" "Workflow actions must not use the old-runtime download-artifact action"
  require_not_contains "$workflow" "actions/download-artifact@v6" "Workflow actions must not use the preliminary Node 24 download-artifact action"
done

require_not_contains "$docs_workflow" "actions/configure-pages@v5" "Documentation workflow must not use the Node 20 configure-pages action"
require_not_contains "$docs_workflow" "actions/deploy-pages@v4" "Documentation workflow must not use the Node 20 deploy-pages action"
require_not_contains "$docs_workflow" "actions/setup-python@v5" "Documentation workflow must not use the Node 20 setup-python action"
require_not_contains "$docs_workflow" "actions/upload-pages-artifact@v4" "Documentation workflow must not use the Node 20 upload-pages-artifact action"
require_contains "$docs_workflow" "pull_request:" "Documentation workflow must validate docs changes on pull requests"
require_contains "$docs_workflow" "./.github/scripts/test-docs-navigation-contract.sh" "Documentation workflow must validate the checked-in navigation mirror"
require_contains "$docs_workflow" "./.github/scripts/test-docs-content-contract.sh" "Documentation workflow must validate install and agent docs contracts"
require_contains "$docs_workflow" "if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'" "Documentation deployment must be limited to main, not pull requests or branch dispatches"

require_contains "$ci_workflow" "Workflow release contracts" "CI must run this workflow contract check"
require_contains "$ci_workflow" "./.github/scripts/test-docs-navigation-contract.sh" "CI must run the docs navigation contract check"
require_contains "$ci_workflow" "./.github/scripts/test-docs-content-contract.sh" "CI must run the docs content contract check"
require_contains "$ci_workflow" "./.github/scripts/test-workspace-sync-status.sh" "CI must validate workspace repo coordination"
require_contains "$ci_workflow" "./.github/scripts/test-release-asset-verifier.sh" "CI must test the release asset verifier"
require_contains "$ci_workflow" "./.github/scripts/test-release-preflight.sh" "CI must test the release preflight helper"
require_contains "$ci_workflow" "./.github/scripts/test-release-provenance-assembler.sh" "CI must test the release provenance assembler"
require_contains "$ci_workflow" "./scripts/smoke-ubuntu-debian-bundle.sh" "CI must smoke the Ubuntu/Debian bundle contract"
require_contains "$ci_workflow" "Ubuntu/Debian install container" "CI must validate Ubuntu/Debian installation in a container"
require_contains "$ci_workflow" "runs-on: ubuntu-24.04" "CI install-container validation must run from an Ubuntu 24.04 runner"
require_contains "$ci_workflow" "Download Rust CLI release asset" "CI install-container validation must use the released Rust CLI asset"
require_contains "$ci_workflow" "--repo amichne/kast-rs" "CI install-container validation must fetch the Rust CLI from kast-rs"
require_contains "$ci_workflow" "Package Ubuntu/Debian CI bundle" "CI install-container validation must package the Ubuntu/Debian bundle"
require_contains "$ci_workflow" "RELEASE_GITHUB_TOKEN || github.token" "CI install-container validation must allow an explicit release token fallback"
require_contains "$ci_workflow" "KAST_UBUNTU_DEBIAN_JAVA_VERSION: \${{ matrix.java-version }}" "CI install-container validation must pass the Java version matrix into Docker"
require_contains "$ci_workflow" "- \"21\"" "CI install-container validation must cover the supported Java 21 runtime"
require_not_contains "$ci_workflow" "- \"17\"" "CI install-container validation must not require unsupported Java 17 runtime startup"
require_contains "$ci_workflow" "validate-ubuntu-debian-bundle-in-docker.sh" "CI install-container validation must run the Docker validator"
require_contains "$ci_workflow" "Analysis server transport" "CI must include an independent analysis-server transport job"
require_contains "$ci_workflow" "io.github.amichne.kast.server.AnalysisServerSocketTest" "Analysis server job must smoke the socket transport"
require_contains "$ci_workflow" "IDEA plugin" "CI must use IDEA plugin naming for the IDE-hosted backend job"
require_contains "$ci_workflow" "id: standalone-dist-cache" "CI must restore the standalone daemon distribution cache"
require_contains "$ci_workflow" "backend-standalone/build/distributions" "CI must cache the standalone daemon distribution output"
require_contains "$ci_workflow" "steps.standalone-dist-cache.outputs.cache-hit != 'true'" "CI must skip the standalone distribution build on cache hits"
require_contains "$ci_workflow" "id: intellij-plugin-dist-cache" "CI must restore the IDEA plugin distribution cache"
require_contains "$ci_workflow" "backend-intellij/build/distributions" "CI must cache the IDEA plugin distribution output"
require_contains "$ci_workflow" "steps.intellij-plugin-dist-cache.outputs.cache-hit != 'true'" "CI must skip the IDEA plugin build on cache hits"
require_not_contains "$ci_workflow" "Native CLI" "Kast JVM CI must not build the Rust-owned CLI"
require_not_contains "$ci_workflow" "graalvm/setup-graalvm@v1" "Kast JVM CI must not install GraalVM"
require_not_contains "$ci_workflow" ":kast-cli:nativeCompile" "Kast JVM CI must not compile a native-image CLI"

require_contains "$release_workflow" "Generate and upload SHA256SUMS" "Release must publish aggregate checksums"
require_contains "$release_workflow" "Preflight stable release automation" "Release must preflight stable-release automation before side effects"
require_contains "$release_workflow" "Require Homebrew token for stable releases" "Stable releases must require the Homebrew tap token before tagging"
require_contains "$release_workflow" "HOMEBREW_TAP_TOKEN: \${{ secrets.HOMEBREW_TAP_TOKEN }}" "Stable release preflight must inspect the Homebrew tap token"
require_contains "$release_workflow" "RELEASE_GITHUB_TOKEN || github.token" "Release publishing must allow an explicit release token fallback"
require_contains "$release_workflow" "Dispatch Homebrew tap update" "Release must dispatch the Homebrew tap update"
require_contains "$release_workflow" "Wait for Homebrew tap update" "Release must wait for the Homebrew tap update"
require_contains "$release_workflow" "gh run watch" "Release must watch the Homebrew tap workflow result"
require_not_contains "$release_workflow" "Package headless agent bundle" "Kast JVM release must not publish the old CLI-plus-daemon bundle"
require_contains "$release_workflow" "Build Ubuntu/Debian bundle" "Release must build the Ubuntu/Debian bundle"
require_not_contains "$release_workflow" "kast-devin" "Release must not use Devin-specific bundle naming"
require_contains "$release_workflow" "kast-ubuntu-debian-x86_64-\${tag}.tar.gz" "Release must publish the Ubuntu/Debian tarball"
require_contains "$release_workflow" "validate-ubuntu-debian-bundle-in-docker.sh" "Release must validate the Ubuntu/Debian bundle in Docker"
require_contains "$release_workflow" "KAST_UBUNTU_DEBIAN_JAVA_VERSION=21" "Release Docker validation must use the supported Java 21 runtime"
require_not_contains "$release_workflow" "for java_version in 17 21" "Release must not require unsupported Java 17 runtime startup"
require_not_contains "$release_workflow" "Build native Kast CLI release asset" "Kast JVM release must not build CLI assets"
require_not_contains "$release_workflow" ":kast-cli:nativeCompile" "Kast JVM release must not compile the native CLI"
require_not_contains "$release_workflow" "--native-binary" "Kast JVM release must not package CLI binaries"
require_contains "$release_workflow" "Build IDEA plugin" "Release must use IDEA plugin naming for the IDE plugin build"
require_contains "$release_workflow" "scripts/assemble-release-provenance.py" "Release must use the tested provenance assembler"
require_contains "$release_provenance_assembler" "EXPECTED_PLATFORMS = {" "Release must validate the complete provenance platform set"
require_contains "$release_provenance_assembler" '"ubuntu-debian-x86_64"' "Release provenance must include the Ubuntu/Debian bundle"
require_not_contains "$release_provenance_assembler" '"devin-ubuntu-x86_64"' "Release provenance must not include Devin-specific platform naming"
require_not_contains "$release_provenance_assembler" '"headless-agent-linux-x64"' "Release provenance must not include the old headless agent bundle"
require_contains "$release_provenance_assembler" "missing_provenance" "Release provenance validation must fail on missing entries"
require_contains "$release_provenance_assembler" "assetDigest" "Release provenance entries must include asset digests"
require_contains "$release_workflow" "expected_assets=(" "Release must validate the complete shipped asset set"
require_contains "$release_workflow" "--repo amichne/kast-rs" "Ubuntu/Debian bundle packaging must fetch the Rust CLI from kast-rs"
require_not_contains "$release_workflow" '"kast-${tag}-macos-arm64.zip"' "Kast JVM release must not require Rust CLI assets"
require_not_contains "$release_workflow" '"kast-headless-agent-${tag}-linux-x64.zip"' "Kast JVM release must not require the old headless agent bundle"
require_contains "$release_workflow" '"kast-ubuntu-debian-x86_64-${tag}.tar.gz"' "Release must require the Ubuntu/Debian bundle asset"
require_contains "$release_workflow" '"kast-intellij-${tag}.zip"' "Release must require the IDEA plugin asset"
require_contains "$release_workflow" '"kast-standalone-${tag}.zip"' "Release must require the standalone backend asset"
require_contains "$release_workflow" 'for asset in "${expected_assets[@]}"; do' "Release must check every expected asset before publishing checksums"
require_contains "$release_workflow" "./scripts/verify-release-assets.sh --release-dir release-assets --tag \"\$tag\"" "Release must verify assets, checksums, and provenance before publishing"
require_contains "$release_workflow" 'gh release view "$tag" >/dev/null 2>&1' "Release preparation must tolerate existing releases"
require_occurrences "$release_workflow" "if: \${{ !contains(needs.prepare-release.outputs.release_tag" 2 "Homebrew tap updates must only run for stable releases"
require_contains "$release_workflow" "client_payload[component]=plugin" "Kast release must update only the plugin formula"
require_contains "$release_workflow" "client_payload[sha256_plugin]=\${sha_plugin}" "Kast release must dispatch the plugin checksum"
require_not_contains "$release_workflow" "needs: [bump-version, prepare-release]" "Release build jobs must not depend on a skipped workflow_dispatch-only job"
require_not_contains "$release_workflow" "      - bump-version" "Release publish job must not depend on a skipped workflow_dispatch-only job"
require_occurrences "$release_workflow" "if: always() && needs.prepare-release.result == 'success'" 4 "Tag-push release jobs must opt out of skipped bump-version poisoning"
require_contains "$release_workflow" "needs.build-intellij-plugin.result == 'success'" "Release publish job must require a successful plugin build"
require_contains "$release_workflow" "needs.build-standalone-backend.result == 'success'" "Release publish job must require a successful standalone backend build"
require_contains "$release_workflow" "needs.build-ubuntu-debian-bundle.result == 'success'" "Release publish job must require a successful Ubuntu/Debian bundle build"
require_contains "$kast_script" "cmd_install_retired" "kast.sh install must route to the retired-installer message"
require_not_contains "$kast_script" "cmd_install()" "kast.sh must not keep the retired shell installer implementation"
require_not_contains "$kast_script" "--components=<list>" "kast.sh must not expose retired installer component flags"
require_not_contains "$kast_script" "KAST_MANAGED_ROOT" "kast.sh must not expose retired installer environment variables"
require_not_contains "$kast_script" "KAST_INSTALL_SOURCE" "kast.sh must not expose retired installer metadata variables"
require_not_contains "$kast_script" "Auto-detect curl/pipe" "kast.sh must not auto-invoke the retired shell installer"
require_contains "$release_workflow" 'release_flags=("--draft=false")' "Release publication must build explicit release flags"
require_contains "$release_workflow" '[[ "$tag" == *-* ]]' "Release publication must detect prerelease tags"
require_contains "$release_workflow" 'release_flags+=(--prerelease)' "Prerelease tags must publish as prereleases"
require_contains "$release_workflow" 'release_flags+=(--latest)' "Stable tags must publish as latest releases"
require_order "$release_workflow" "Generate and upload SHA256SUMS" "Publish draft release with provenance annotation" "Release must upload checksum manifest before publishing"
require_order "$release_workflow" "Preflight stable release automation" "Bump version" "Release must check stable-release automation before tagging"
require_order "$release_workflow" "Preflight stable release automation" "Prepare release" "Release must check stable-release automation before creating releases"
require_order "$release_workflow" "Publish draft release with provenance annotation" "Dispatch Homebrew tap update" "Release must publish GitHub assets before updating Homebrew"
require_order "$release_workflow" "Dispatch Homebrew tap update" "Wait for Homebrew tap update" "Release must wait only after dispatching Homebrew"

require_not_contains "$settings_file" ":kast-cli" "Old JVM CLI module must not be included in Gradle settings"
require_not_contains "$root_build_file" "stageCliDist" "Root build must not stage the old JVM CLI"
require_not_contains "$root_build_file" "buildCliPortableZip" "Root build must not package the old JVM CLI"
require_not_contains "$copilot_setup_workflow" ":kast-cli" "Copilot setup must not build the old JVM CLI"

require_contains "$ubuntu_debian_docker_validator" "KAST_UBUNTU_DEBIAN_JAVA_VERSION" "Ubuntu/Debian Docker validator must accept an explicit Java version"
require_contains "$ubuntu_debian_docker_validator" "ubuntu:24.04" "Ubuntu/Debian Docker validator must default to Ubuntu 24.04"
require_contains "$ubuntu_debian_docker_validator" "--platform linux/amd64" "Ubuntu/Debian Docker validator must match the x86_64 bundle platform"
require_contains "$ubuntu_debian_docker_validator" "openjdk-\${java_version}-jdk-headless" "Ubuntu/Debian Docker validator must install the selected JDK"
require_contains "$ubuntu_debian_docker_validator" "kast up --workspace-root=/workspace" "Ubuntu/Debian Docker validator must start the installed standalone backend"
require_contains "$ubuntu_debian_docker_validator" "kast capabilities --workspace-root=/workspace" "Ubuntu/Debian Docker validator must query the installed backend"
require_contains "$ubuntu_debian_packager" "COPYFILE_DISABLE=1" "Ubuntu/Debian packager must suppress macOS copyfile metadata"
require_contains "$ubuntu_debian_packager" "--no-xattrs" "Ubuntu/Debian packager must omit host extended attributes"

printf '%s\n' "Release workflow contract test passed"
