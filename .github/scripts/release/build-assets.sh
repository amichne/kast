#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "build-release-assets: $*" >&2
  exit 1
}

version=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || fail "--version requires a value"
      version="$2"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  fail "version must be <major>.<minor>.<patch>"

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "${repository_root}"

./gradlew \
  -Dorg.gradle.jvmargs=-Xmx5g \
  -Pversion="${version}" \
  assembleIdeHostedRelease

control_name="kast-control-v${version}-macos-aarch64.tar.gz"
plugin_name="kast-ide-plugin-${version}.zip"
control_source="${repository_root}/build/ide-hosted-release-assets/${control_name}"
plugin_source="${repository_root}/ide-plugin/build/distributions/${plugin_name}"
[[ -f "${control_source}" ]] || fail "missing control artifact: ${control_source}"
[[ -f "${plugin_source}" ]] || fail "missing plugin artifact: ${plugin_source}"

output_directory="${repository_root}/build/release/v${version}"
mkdir -p "${output_directory}"
rm -f -- \
  "${output_directory}/${control_name}" \
  "${output_directory}/${control_name}.sha256" \
  "${output_directory}/${plugin_name}" \
  "${output_directory}/${plugin_name}.sha256"
cp "${control_source}" "${output_directory}/${control_name}"
cp "${plugin_source}" "${output_directory}/${plugin_name}"
(
  cd "${output_directory}"
  shasum -a 256 "${control_name}" >"${control_name}.sha256"
  shasum -a 256 "${plugin_name}" >"${plugin_name}.sha256"
)

python3 distribution/release/verify_assets.py \
  --directory "${output_directory}" \
  --release "v${version}" \
  --repository "${GITHUB_REPOSITORY:-amichne/kast}" \
  --report "${repository_root}/build/reports/ide-hosted/release-assets.json"
