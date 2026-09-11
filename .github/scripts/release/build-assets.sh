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

[[ "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must be <major>.<minor>.<patch>"
[[ "${expected_source_revision}" =~ ^[0-9a-f]{40}$ ]] || fail "source revision must be one full Git identity"

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "${repository_root}"
source_revision="$(
  "${repository_root}/.github/scripts/release/admit-source.sh" \
    --repository-root "${repository_root}" \
    --expected-source-revision "${expected_source_revision}"
)"

./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  -Pversion="${version}" -PkastSourceRevision="${source_revision}" \
  productBuildGate assembleSidecarRelease :runtime:hosted:hostedPlugin generateKastModuleKnowledge

"${repository_root}/.github/scripts/release/admit-source.sh" \
  --repository-root "${repository_root}" \
  --expected-source-revision "${source_revision}" >/dev/null

control_name="kast-control-v${version}-macos-aarch64.tar.gz"
sidecar_name="kast-semantic-runtime-${version}-macos-aarch64.zip"
idea_build="$(sed -nE 's/^ide-host-build = "([^"]+)"/\1/p' gradle/libs.versions.toml)"
[[ "${idea_build}" =~ ^[0-9]+(\.[0-9]+)+$ ]] || fail "IDEA host build is invalid"
plugin_name="kast-ide-hosted-v${version}-idea-${idea_build%%.*}.zip"
schema_name="kast-cli-schema-v${version}.json"
knowledge_name="kast-module-knowledge-v${version}.json"
sbom_name="kast-sbom-v${version}.cdx.json"
control_source="${repository_root}/build/distributions/${control_name}"
sidecar_source="${repository_root}/build/distributions/${sidecar_name}"
plugin_source="${repository_root}/runtime/hosted/build/distributions/${plugin_name}"
knowledge_source="${repository_root}/build/reports/kast-architecture/kast-module-knowledge.json"
for source in "${control_source}" "${sidecar_source}" "${plugin_source}" "${knowledge_source}"; do
  [[ -f "${source}" ]] || fail "missing build output: ${source}"
done

output_directory="${repository_root}/build/release/v${version}"
rm -rf -- "${output_directory}"
mkdir -p "${output_directory}"
cp "${control_source}" "${output_directory}/${control_name}"
cp "${sidecar_source}" "${output_directory}/${sidecar_name}"
cp "${plugin_source}" "${output_directory}/${plugin_name}"
cp "${knowledge_source}" "${output_directory}/${knowledge_name}"

schema_control="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-schema.XXXXXX")"
cleanup() { rm -rf -- "${schema_control}"; }
trap cleanup EXIT
tar -xzf "${control_source}" -C "${schema_control}"
mkdir -p "${schema_control}/home"
HOME="${schema_control}/home" JAVA_OPTS="-Duser.home=${schema_control}/home" \
  "${schema_control}/bin/kast" --schema >"${output_directory}/${schema_name}"

python3 distribution/release/generate_sbom.py \
  --source-root "${repository_root}" --assets-directory "${output_directory}" \
  --version "${version}" --source-revision "${source_revision}"

(
  cd "${output_directory}"
  for asset in "${control_name}" "${sidecar_name}" "${plugin_name}" "${schema_name}" "${knowledge_name}" "${sbom_name}"; do
    [[ -f "${asset}" ]] || fail "missing release asset: ${asset}"
    shasum -a 256 "${asset}" >"${asset}.sha256"
  done
)

printf '%s\n' "build-release-assets: built exact-source artifacts for ${source_revision}"
