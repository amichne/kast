#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/verify-release-state.sh --tag <vX.Y.Z> [options]

Verify that a Kast release is fully published:
  - GitHub release exists, is not draft, and has the expected stable/prerelease state.
  - Release assets, SHA256SUMS, and build-provenance.json pass verification.
  - Public Maven Central modules are available.
  - Stable releases are the GitHub latest release and are reflected in Homebrew.

Options:
  --repository <owner/repo>           GitHub repository to verify. Defaults to GITHUB_REPOSITORY or amichne/kast.
  --homebrew-repo <owner/repo>        Homebrew tap repository. Defaults to amichne/homebrew-kast.
  --work-dir <dir>                    Directory for downloaded assets and tap clone. Defaults to a temp dir.
  --maven-attempts <n>                Maven verification attempts. Defaults to 1.
  --maven-delay-seconds <n>           Delay between Maven attempts. Defaults to 0.
USAGE
}

tag=""
repository="${GITHUB_REPOSITORY:-amichne/kast}"
homebrew_repo="amichne/homebrew-kast"
work_dir=""
maven_attempts=1
maven_delay_seconds=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"; shift 2 ;;
    --tag=*)
      tag="${1#--tag=}"; shift ;;
    --repository)
      [[ $# -ge 2 ]] || die "Missing value for --repository"
      repository="$2"; shift 2 ;;
    --repository=*)
      repository="${1#--repository=}"; shift ;;
    --homebrew-repo)
      [[ $# -ge 2 ]] || die "Missing value for --homebrew-repo"
      homebrew_repo="$2"; shift 2 ;;
    --homebrew-repo=*)
      homebrew_repo="${1#--homebrew-repo=}"; shift ;;
    --work-dir)
      [[ $# -ge 2 ]] || die "Missing value for --work-dir"
      work_dir="$2"; shift 2 ;;
    --work-dir=*)
      work_dir="${1#--work-dir=}"; shift ;;
    --maven-attempts)
      [[ $# -ge 2 ]] || die "Missing value for --maven-attempts"
      maven_attempts="$2"; shift 2 ;;
    --maven-attempts=*)
      maven_attempts="${1#--maven-attempts=}"; shift ;;
    --maven-delay-seconds)
      [[ $# -ge 2 ]] || die "Missing value for --maven-delay-seconds"
      maven_delay_seconds="$2"; shift 2 ;;
    --maven-delay-seconds=*)
      maven_delay_seconds="${1#--maven-delay-seconds=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$tag" ]] || { usage; die "--tag is required"; }
[[ "$tag" == v* ]] || die "--tag must start with v: $tag"
[[ "$repository" == */* ]] || die "--repository must look like owner/repo"
[[ "$homebrew_repo" == */* ]] || die "--homebrew-repo must look like owner/repo"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/.." && pwd)"
version="${tag#v}"
stable=true
if [[ "$tag" == *-* ]]; then
  stable=false
fi

cleanup_dir=""
if [[ -z "$work_dir" ]]; then
  cleanup_dir="$(mktemp -d)"
  work_dir="$cleanup_dir"
fi
trap '[[ -z "${cleanup_dir:-}" ]] || rm -rf "$cleanup_dir"' EXIT
mkdir -p "$work_dir"

require_false() {
  local value="$1"
  local message="$2"
  [[ "$value" == false ]] || die "$message"
}

require_true() {
  local value="$1"
  local message="$2"
  [[ "$value" == true ]] || die "$message"
}

is_draft="$(gh release view "$tag" --repo "$repository" --json isDraft --jq .isDraft)"
is_prerelease="$(gh release view "$tag" --repo "$repository" --json isPrerelease --jq .isPrerelease)"
require_false "$is_draft" "GitHub release ${tag} is still a draft"
if [[ "$stable" == true ]]; then
  require_false "$is_prerelease" "Stable release ${tag} is marked prerelease"
  latest_tag="$(gh api "repos/${repository}/releases/latest" --jq .tag_name)"
  [[ "$latest_tag" == "$tag" ]] || die "Stable release ${tag} is not latest; latest is ${latest_tag}"
else
  require_true "$is_prerelease" "Prerelease ${tag} is not marked prerelease"
fi

release_dir="${work_dir}/release-assets"
rm -rf "$release_dir"
mkdir -p "$release_dir"
gh release download "$tag" --repo "$repository" --dir "$release_dir" --pattern '*.zip'
gh release download "$tag" --repo "$repository" --dir "$release_dir" --pattern '*.tar.gz' || true
gh release download "$tag" --repo "$repository" --dir "$release_dir" --pattern '*.tar.gz.sha256' || true
gh release download "$tag" --repo "$repository" --dir "$release_dir" --pattern 'build-provenance.json'
gh release download "$tag" --repo "$repository" --dir "$release_dir" --pattern 'SHA256SUMS'
"${repo_root}/scripts/verify-release-assets.sh" --release-dir "$release_dir" --tag "$tag"

"${repo_root}/scripts/verify-maven-central.sh" \
  --version "$version" \
  --attempts "$maven_attempts" \
  --delay-seconds "$maven_delay_seconds"

if [[ "$stable" == true ]]; then
  tap_dir="${work_dir}/homebrew-tap"
  rm -rf "$tap_dir"
  gh repo clone "$homebrew_repo" "$tap_dir" -- --depth 1 >/dev/null
  ruby -c "${tap_dir}/Formula/kast.rb" >/dev/null
  ruby -c "${tap_dir}/Casks/kast-plugin.rb" >/dev/null
  python3 - "$tag" "${release_dir}/SHA256SUMS" "$tap_dir" <<'PY'
import json
import re
import sys
from pathlib import Path

tag = sys.argv[1]
version = tag.removeprefix("v")
sha_file = Path(sys.argv[2])
tap_dir = Path(sys.argv[3])

def fail(message: str) -> None:
    raise SystemExit(message)

state = json.loads((tap_dir / "release-state.json").read_text(encoding="utf-8"))
if state.get("current_release") != tag:
    fail(f"homebrew release-state.json current_release is {state.get('current_release')!r}, expected {tag!r}")

formula = (tap_dir / "Formula" / "kast.rb").read_text(encoding="utf-8")
cask = (tap_dir / "Casks" / "kast-plugin.rb").read_text(encoding="utf-8")
if f'ARTIFACT_VERSION = "{version}"' not in formula:
    fail("Formula/kast.rb does not name the release version")
if f'artifact_version = "{version}"' not in cask:
    fail("Casks/kast-plugin.rb does not name the release version")

sha_entries: dict[str, str] = {}
for raw_line in sha_file.read_text(encoding="utf-8").splitlines():
    parts = raw_line.split()
    if len(parts) == 2:
        sha_entries[parts[1]] = parts[0]

formula_assets = [
    f"kast-{tag}-linux-arm64.zip",
    f"kast-{tag}-linux-x64.zip",
    f"kast-{tag}-macos-arm64.zip",
    f"kast-{tag}-macos-x64.zip",
]
cask_assets = [f"kast-intellij-{tag}.zip"]

for asset_name in formula_assets:
    digest = sha_entries.get(asset_name)
    if digest is None:
        fail(f"SHA256SUMS is missing {asset_name}")
    if not re.search(rf'\bsha256 "{re.escape(digest)}"', formula):
        fail(f"Formula/kast.rb is missing checksum for {asset_name}")

for asset_name in cask_assets:
    digest = sha_entries.get(asset_name)
    if digest is None:
        fail(f"SHA256SUMS is missing {asset_name}")
    if not re.search(rf'\bsha256 "{re.escape(digest)}"', cask):
        fail(f"Casks/kast-plugin.rb is missing checksum for {asset_name}")
PY
fi

printf 'Verified published release state for %s\n' "$tag"
