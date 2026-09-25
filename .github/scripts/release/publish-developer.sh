#!/usr/bin/env bash
set -euo pipefail

fail() { echo "publish-developer: $*" >&2; exit 1; }

version=""
commit=""
assets_directory=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) [[ $# -ge 2 ]] || fail "--version requires a value"; version="$2"; shift 2 ;;
    --commit) [[ $# -ge 2 ]] || fail "--commit requires a value"; commit="$2"; shift 2 ;;
    --assets-directory) [[ $# -ge 2 ]] || fail "--assets-directory requires a value"; assets_directory="$2"; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "$version" =~ ^0\.0\.[0-9]+$ ]] || fail "version must be 0.0.<build>"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "commit must be one full Git identity"
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required"
[[ -d "$assets_directory" ]] || fail "assets directory is missing"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "$root"
assets_directory="$(cd "$assets_directory" && pwd -P)"
repository="${GITHUB_REPOSITORY:-amichne/kast}"
python3 .github/scripts/release/ci-candidate.py validate \
  --repository "$repository" --version "$version" \
  --source-revision "$commit" --directory "$assets_directory"

tag="developer-v$version"
if gh release view "$tag" --repo "$repository" >/dev/null 2>&1; then
  fail "immutable developer release already exists: $tag"
fi

control="$assets_directory/kast-control-v$version-macos-aarch64.tar.gz"
plugins=("$assets_directory"/kast-ide-hosted-v"$version"-idea-*.zip)
[[ "${#plugins[@]}" == 1 && -f "${plugins[0]}" ]] || fail "expected one hosted plugin"
plugin="${plugins[0]}"
catalog="$assets_directory/kast-hosted-catalog-v$version.json"
knowledge="$assets_directory/kast-module-knowledge-v$version.json"
sbom="$assets_directory/kast-sbom-v$version.cdx.json"
assets=("$control" "$control.sha256" "$plugin" "$plugin.sha256"
  "$catalog" "$catalog.sha256" "$knowledge" "$knowledge.sha256" "$sbom" "$sbom.sha256")

gh release create "$tag" "${assets[@]}" --repo "$repository" --target "$commit" \
  --prerelease --latest=false --title "Kast developer $version" \
  --notes "Exact-source developer build from $commit. The SBOM and checksums are included."

if ! gh release view developer-latest --repo "$repository" >/dev/null 2>&1; then
  gh release create developer-latest --repo "$repository" --target "$commit" \
    --prerelease --latest=false --title "Kast developer latest" \
    --notes "Mutable channel pointer only. Read latest.txt for the exact immutable developer release and source revision."
fi

pointer="$assets_directory/latest.txt"
printf '%s %s %s\n' "$tag" "$version" "$commit" > "$pointer"
gh release upload developer-latest "$pointer#latest.txt" --clobber --repo "$repository"
gh release view "$tag" --repo "$repository" --json tagName,targetCommitish,url,assets
gh release view developer-latest --repo "$repository" --json tagName,url,assets
