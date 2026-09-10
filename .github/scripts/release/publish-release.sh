#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "publish-release: $*" >&2
  exit 1
}

release=""
commit=""
assets_directory=""
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
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "${release}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "release must be v<major>.<minor>.<patch>"
[[ "${commit}" =~ ^[0-9a-f]{40}$ ]] || fail "commit must be one full Git identity"
[[ -d "${assets_directory}" ]] || fail "assets directory does not exist"
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
sidecar="${assets_directory}/kast-semantic-runtime-${version}-macos-aarch64.zip"
plugins=("${assets_directory}"/kast-ide-hosted-v"${version}"-idea-*.zip)
[[ "${#plugins[@]}" == 1 && -f "${plugins[0]}" ]] || fail "expected one IDEA-build-specific hosted plugin"
plugin="${plugins[0]}"
schema="${assets_directory}/kast-cli-schema-v${version}.json"
knowledge="${assets_directory}/kast-module-knowledge-v${version}.json"
sbom="${assets_directory}/kast-sbom-v${version}.cdx.json"
assets=(
  "${control}"
  "${control}.sha256"
  "${sidecar}"
  "${sidecar}.sha256"
  "${plugin}"
  "${plugin}.sha256"
  "${schema}"
  "${schema}.sha256"
  "${knowledge}"
  "${knowledge}.sha256"
  "${sbom}"
  "${sbom}.sha256"
)
for asset in "${assets[@]}"; do
  [[ -f "${asset}" ]] || fail "missing release asset: ${asset}"
done

upload_assets=(
  "${control}#kast-control-v${version}-macos-aarch64"
  "${control}.sha256"
  "${sidecar}#kast-semantic-runtime-v${version}-macos-aarch64"
  "${sidecar}.sha256"
  "${plugin}#kast-ide-hosted-v${version}-idea-compatible"
  "${plugin}.sha256"
  "${schema}"
  "${schema}.sha256"
  "${knowledge}"
  "${knowledge}.sha256"
  "${sbom}"
  "${sbom}.sha256"
)
release_notes="$(
  printf '%s\n' \
    "Kast installs one public \`kast\` command from three matched payloads:" \
    '' \
    '- **Control:** CLI parsing, lifecycle, schemas, broker, and typed wire transport.' \
    '- **Private semantic runtime:** the headless indexer and compiler integration loaded with the supported local IDEA.' \
    '- **Existing-IDE plugin:** the exact-build plugin installed programmatically by the verified shell installer.'
)"

gh release create "${release}" "${upload_assets[@]}" \
  --repo "${repository}" \
  --target "${commit}" \
  --title "Kast ${release}" \
  --generate-notes \
  --notes "${release_notes}"

gh release view "${release}" --repo "${repository}" \
  --json tagName,targetCommitish,isDraft,publishedAt,url,assets
