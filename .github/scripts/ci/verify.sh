#!/usr/bin/env bash
# Manual release candidate build: exact source in, publishable artifacts out.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "$root"
sha="$(git rev-parse HEAD)"
bash .github/scripts/release/admit-source.sh --repository-root "$root" --expected-source-revision "$sha" >/dev/null
if git symbolic-ref -q HEAD >/dev/null; then
  echo 'ci-preflight: detached checkout required' >&2
  exit 1
fi
export JAVA_HOME="${KAST_RELEASE_JDK_25:?Set KAST_RELEASE_JDK_25 to a Java 25 home}"
version="$(cat distribution/release/candidate-version.txt)"
bash .github/scripts/release/build-assets.sh --version "${version#v}" --source-revision "$sha"
printf '%s\n' "release-candidate: built ${version} from ${sha}"
