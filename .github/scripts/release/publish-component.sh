#!/usr/bin/env bash
set -euo pipefail

fail() { echo "publish-component: $*" >&2; exit 1; }
component="" version="" commit="" assets_directory=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --component) component="$2"; shift 2 ;;
    --version) version="$2"; shift 2 ;;
    --commit) commit="$2"; shift 2 ;;
    --assets-directory) assets_directory="$2"; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ "$component" == control || "$component" == host ]] || fail "component must be control or host"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must be stable semver"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "commit must be a full Git identity"
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required"
repository="${GITHUB_REPOSITORY:-amichne/kast}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "$repository_root"
"$repository_root/.github/scripts/release/admit-source.sh" \
  --repository-root "$repository_root" --expected-source-revision "$commit" >/dev/null
git fetch --no-tags origin main
[[ "$(git rev-parse origin/main)" == "$commit" ]] || fail "release source is not exact current origin/main"
python3 distribution/release/component_release.py --component "$component" --version "$version" \
  --source-revision "$commit" --directory "$assets_directory"
tag="${component}-v${version}"
if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then fail "tag already exists: $tag"; fi
if gh release view "$tag" --repo "$repository" >/dev/null 2>&1; then fail "release already exists: $tag"; fi
assets=("$assets_directory"/*)
gh release create "$tag" "${assets[@]}" --repo "$repository" --target "$commit" \
  --title "Kast ${component} ${version}" --generate-notes --latest=false \
  --notes "Independent Kast ${component} release. Compatibility is determined by the declared hosted contract; the other component is installed and versioned separately."
gh release view "$tag" --repo "$repository" --json tagName,targetCommitish,isDraft,publishedAt,url,assets
