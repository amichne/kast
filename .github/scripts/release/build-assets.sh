#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "build-release-assets: $*" >&2
  exit 1
}

version=""
expected_source_revision=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || fail "--version requires a value"
      version="$2"
      shift 2
      ;;
    --source-revision)
      [[ $# -ge 2 ]] || fail "--source-revision requires a value"
      expected_source_revision="$2"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  fail "version must be <major>.<minor>.<patch>"
[[ "${expected_source_revision}" =~ ^[0-9a-f]{40}$ ]] ||
  fail "source revision must be one full Git identity"

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "${repository_root}"
source_revision="$(
  "${repository_root}/.github/scripts/release/admit-source.sh" \
    --repository-root "${repository_root}" \
    --expected-source-revision "${expected_source_revision}"
)"

python3 distribution/release/release_gate.py source \
  --source-root "${repository_root}" \
  --assets-directory "${repository_root}/build/release/v${version}" \
  --version "${version}" --source-revision "${source_revision}"
post_build_source_revision="$(
  "${repository_root}/.github/scripts/release/admit-source.sh" \
    --repository-root "${repository_root}" \
    --expected-source-revision "${source_revision}"
)"
[[ "${post_build_source_revision}" == "${source_revision}" ]] ||
  fail "source identity changed during the release build"

control_name="kast-control-v${version}-macos-aarch64.tar.gz"
sidecar_name="kast-semantic-runtime-${version}-macos-aarch64.zip"
schema_name="kast-cli-schema-v${version}.json"
knowledge_name="kast-module-knowledge-v${version}.json"
control_source="${repository_root}/build/distributions/${control_name}"
sidecar_source="${repository_root}/build/distributions/${sidecar_name}"
knowledge_source="${repository_root}/build/reports/kast-architecture/kast-module-knowledge.json"
[[ -f "${control_source}" ]] || fail "missing control artifact: ${control_source}"
[[ -f "${sidecar_source}" ]] || fail "missing sidecar artifact: ${sidecar_source}"
[[ -f "${knowledge_source}" ]] || fail "missing module knowledge: ${knowledge_source}"

output_directory="${repository_root}/build/release/v${version}"
mkdir -p "${output_directory}"
rm -f -- \
  "${output_directory}/${control_name}" \
  "${output_directory}/${control_name}.sha256" \
  "${output_directory}/${sidecar_name}" \
  "${output_directory}/${sidecar_name}.sha256" \
  "${output_directory}/${schema_name}" \
  "${output_directory}/${schema_name}.sha256" \
  "${output_directory}/${knowledge_name}" \
  "${output_directory}/${knowledge_name}.sha256"
cp "${control_source}" "${output_directory}/${control_name}"
cp "${sidecar_source}" "${output_directory}/${sidecar_name}"
cp "${knowledge_source}" "${output_directory}/${knowledge_name}"

schema_control="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-schema.XXXXXX")"
cleanup() {
  rm -rf -- "${schema_control}"
}
trap cleanup EXIT
tar -xzf "${control_source}" -C "${schema_control}"
mkdir -p "${schema_control}/home"
HOME="${schema_control}/home" JAVA_OPTS="-Duser.home=${schema_control}/home" \
  "${schema_control}/bin/kast" --schema >"${output_directory}/${schema_name}"
(
  cd "${output_directory}"
  shasum -a 256 "${control_name}" >"${control_name}.sha256"
  shasum -a 256 "${sidecar_name}" >"${sidecar_name}.sha256"
  shasum -a 256 "${schema_name}" >"${schema_name}.sha256"
  shasum -a 256 "${knowledge_name}" >"${knowledge_name}.sha256"
)

python3 distribution/release/verify_assets.py \
  --directory "${output_directory}" \
  --release "v${version}" \
  --source-revision "${source_revision}" \
  --source-root "${repository_root}" \
  --repository "${GITHUB_REPOSITORY:-amichne/kast}" \
  --report "${repository_root}/build/reports/sidecar/release-assets.json"

python3 distribution/release/generate_sbom.py \
  --source-root "${repository_root}" --assets-directory "${output_directory}" \
  --version "${version}" --source-revision "${source_revision}"

python3 integration-tests/release_artifact_acceptance.py \
  --assets-directory "${output_directory}" \
  --version "${version}" --source-revision "${source_revision}"

python3 distribution/release/release_gate.py finish \
  --source-root "${repository_root}" --assets-directory "${output_directory}" \
  --version "${version}" --source-revision "${source_revision}"
