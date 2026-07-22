#!/usr/bin/env bash
set -Eeuo pipefail

tag=""
repository="${GITHUB_REPOSITORY:-amichne/kast}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) tag="$2"; shift 2 ;;
    --repository) repository="$2"; shift 2 ;;
    *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[[ -n "$tag" ]] || { printf '%s\n' '--tag is required' >&2; exit 2; }

work="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-verify.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
asset="kast-linux-x64-${tag}.tar.gz"
gh release download "$tag" --repo "$repository" --dir "$work" --pattern "$asset" --pattern "${asset}.sha256"
(cd "$work" && sha256sum -c "${asset}.sha256")
"$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/verify-setup-bundle.sh" "$work/$asset"
printf 'release setup verification passed for %s\n' "$tag"
