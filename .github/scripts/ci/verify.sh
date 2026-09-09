#!/usr/bin/env bash
# Manual release candidate build: exact source plus resolved version in, publishable artifacts out.
set -euo pipefail

version=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || { echo 'release-candidate: --version requires a value' >&2; exit 2; }
      version="$2"
      shift 2
      ;;
    *)
      echo "release-candidate: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo 'release-candidate: --version must be <major>.<minor>.<patch>' >&2
  exit 2
}

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "$root"
sha="$(git rev-parse HEAD)"
bash .github/scripts/release/admit-source.sh --repository-root "$root" --expected-source-revision "$sha" >/dev/null
if git symbolic-ref -q HEAD >/dev/null; then
  echo 'ci-preflight: detached checkout required' >&2
  exit 1
fi
export JAVA_HOME="${KAST_RELEASE_JDK_25:?Set KAST_RELEASE_JDK_25 to a Java 25 home}"
unset KAST_RELEASE_JDK_25
bash .github/scripts/release/build-assets.sh --version "$version" --source-revision "$sha"
printf '%s\n' "release-candidate: built v${version} from ${sha}"
