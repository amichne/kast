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

[[ "${release}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  fail "release must be v<major>.<minor>.<patch>"
[[ "${commit}" =~ ^[0-9a-f]{40}$ ]] || fail "commit must be one full Git identity"
[[ -d "${assets_directory}" ]] || fail "assets directory does not exist"
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required"
repository="${GITHUB_REPOSITORY:-amichne/kast}"
version="${release#v}"

git fetch --no-tags origin main
main_commit="$(git rev-parse origin/main)"
[[ "${main_commit}" == "${commit}" ]] ||
  fail "release commit ${commit} is not the exact origin/main commit ${main_commit}"
if git ls-remote --exit-code --tags origin "refs/tags/${release}" >/dev/null 2>&1; then
  fail "tag already exists: ${release}"
fi
if gh release view "${release}" --repo "${repository}" >/dev/null 2>&1; then
  fail "release already exists: ${release}"
fi

assets_directory="$(cd "${assets_directory}" && pwd -P)"
control="${assets_directory}/kast-control-v${version}-macos-aarch64.tar.gz"
sidecar="${assets_directory}/kast-semantic-runtime-${version}-macos-aarch64.zip"
schema="${assets_directory}/kast-cli-schema-v${version}.json"
knowledge="${assets_directory}/kast-module-knowledge-v${version}.json"
assets=(
  "${control}"
  "${control}.sha256"
  "${sidecar}"
  "${sidecar}.sha256"
  "${schema}"
  "${schema}.sha256"
  "${knowledge}"
  "${knowledge}.sha256"
)
for asset in "${assets[@]}"; do
  [[ -f "${asset}" ]] || fail "missing release asset: ${asset}"
done

gh release create "${release}" "${assets[@]}" \
  --repo "${repository}" \
  --target "${commit}" \
  --title "Kast ${release}" \
  --generate-notes \
  --draft

verification_directory="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-draft.XXXXXX")"
cleanup() {
  rm -rf -- "${verification_directory}"
}
trap cleanup EXIT
gh release download "${release}" --repo "${repository}" --dir "${verification_directory}"
python3 distribution/release/verify_assets.py \
  --directory "${verification_directory}" \
  --release "${release}" \
  --source-revision "${commit}" \
  --repository "${repository}"
gh release edit "${release}" --repo "${repository}" --draft=false --latest
gh release view "${release}" --repo "${repository}" \
  --json tagName,targetCommitish,isDraft,publishedAt,url,assets
