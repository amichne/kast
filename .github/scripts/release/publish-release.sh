#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "publish-release: $*" >&2
  exit 1
}

release=""
commit=""
assets_directory=""
runtimes_directory=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release)
      [[ $# -ge 2 ]] || fail "--release requires a value"
      release="$2"
      shift 2
      ;;
    --commit)
      [[ $# -ge 2 ]] || fail "--commit requires a value"
      commit="$2"
      shift 2
      ;;
    --assets-directory)
      [[ $# -ge 2 ]] || fail "--assets-directory requires a value"
      assets_directory="$2"
      shift 2
      ;;
    --runtimes-directory)
      [[ $# -ge 2 ]] || fail "--runtimes-directory requires a value"
      runtimes_directory="$2"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "${release}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "release must be v<major>.<minor>.<patch>"
[[ "${commit}" =~ ^[0-9a-f]{40}$ ]] || fail "commit must be one full Git identity"
[[ -d "${assets_directory}" ]] || fail "assets directory does not exist"
[[ -d "${runtimes_directory}" ]] || fail "qualified runtimes directory does not exist"
runtimes_directory="$(cd "${runtimes_directory}" && pwd -P)"
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required"
repository="${GITHUB_REPOSITORY:-amichne/kast}"
version="${release#v}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "${repository_root}"

source_revision="$(
  "${repository_root}/.github/scripts/release/admit-source.sh" \
    --repository-root "${repository_root}" \
    --expected-source-revision "${commit}"
)"
[[ "${source_revision}" == "${commit}" ]] || fail "source admission returned a mismatched release commit"

git fetch --no-tags origin main
main_commit="$(git rev-parse origin/main)"
[[ "${main_commit}" == "${commit}" ]] || fail "release commit ${commit} is not the exact origin/main commit ${main_commit}"

if git ls-remote --exit-code --tags origin "refs/tags/${release}" >/dev/null 2>&1; then
  fail "tag already exists: ${release}"
fi
if gh release view "${release}" --repo "${repository}" >/dev/null 2>&1; then
  fail "release already exists: ${release}"
fi

assets_directory="$(cd "${assets_directory}" && pwd -P)"
control="${assets_directory}/kast-control-v${version}-macos-aarch64.tar.gz"
plugins=("${assets_directory}"/kast-ide-hosted-v"${version}"-idea-*.zip)
[[ ( "${#plugins[@]}" == 1 || "${#plugins[@]}" == 2 ) && -f "${plugins[0]}" ]] || fail "expected the selected Host or reviewed Host pair"
if [[ "${#plugins[@]}" == 2 ]]; then
  [[ "${plugins[0]}" == "${assets_directory}/kast-ide-hosted-v${version}-idea-262.zip" &&
     "${plugins[1]}" == "${assets_directory}/kast-ide-hosted-v${version}-idea-263.zip" ]] || fail "unsupported Host pair"
fi
plugin="${plugins[0]}"
catalog="${assets_directory}/kast-hosted-catalog-v${version}.json"
knowledge="${assets_directory}/kast-module-knowledge-v${version}.json"
sbom="${assets_directory}/kast-sbom-v${version}.cdx.json"
agent_plugin="${assets_directory}/kast-plugin-v${version}.zip"
marketplace="${assets_directory}/kast-marketplace-v${version}.zip"
control_record="${assets_directory}/kast-control-release-v${version}.json"
host_record="${assets_directory}/kast-host-release-v${version}.json"
host_installer="${assets_directory}/host-installation.py"
assets=(
  "${control}"
  "${control}.sha256"
  "${plugin}"
  "${plugin}.sha256"
  "${agent_plugin}"
  "${agent_plugin}.sha256"
  "${marketplace}"
  "${marketplace}.sha256"
  "${control_record}"
  "${control_record}.sha256"
  "${host_record}"
  "${host_record}.sha256"
  "${host_installer}"
  "${host_installer}.sha256"
  "${catalog}"
  "${catalog}.sha256"
  "${knowledge}"
  "${knowledge}.sha256"
  "${sbom}"
  "${sbom}.sha256"
)
if [[ "${#plugins[@]}" == 2 ]]; then
  variant_record="${assets_directory}/kast-host-release-v${version}-idea-263.json"
  assets+=("${plugins[1]}" "${plugins[1]}.sha256" "$variant_record" "$variant_record.sha256")
fi
for asset in "${assets[@]}"; do
  [[ -f "${asset}" ]] || fail "missing release asset: ${asset}"
done

upload_assets=(
  "${control}#kast-control-v${version}-macos-aarch64"
  "${control}.sha256"
  "${plugin}#kast-ide-hosted-v${version}-idea-compatible"
  "${plugin}.sha256"
  "${agent_plugin}"
  "${agent_plugin}.sha256"
  "${marketplace}"
  "${marketplace}.sha256"
  "${control_record}"
  "${control_record}.sha256"
  "${host_record}"
  "${host_record}.sha256"
  "${host_installer}"
  "${host_installer}.sha256"
  "${catalog}"
  "${catalog}.sha256"
  "${knowledge}"
  "${knowledge}.sha256"
  "${sbom}"
  "${sbom}.sha256"
)
if [[ "${#plugins[@]}" == 2 ]]; then
  upload_assets+=("${plugins[1]}" "${plugins[1]}.sha256" "$variant_record" "$variant_record.sha256")
fi
runtime_assets="$(
  mise exec -- uv run --locked --script .github/scripts/release/qualified_runtime_assets.py \
    --directory "${runtimes_directory}" --version "${version}" --source-revision "${commit}"
)" || fail "qualified runtime assets rejected"
while IFS= read -r asset; do
  upload_assets+=("${runtimes_directory}/${asset}")
done <<< "${runtime_assets}"

# Retain legacy admission diagnostics, but never publish an independently assembled pair.
fail "complete immutable build record required; use publish_developer.py stable with an exact preview tag and record digest"
