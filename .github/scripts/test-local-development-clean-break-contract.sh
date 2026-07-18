#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/../.." && pwd)"
tasks="$(${repo_root}/gradlew -q tasks --all --no-daemon)"

grep -Eq '^refreshDevelopmentLocal[[:space:]]+-' <<<"$tasks" \
  || die 'refreshDevelopmentLocal must remain the sole local build-and-activate entrypoint'

for retired in \
  installDevelopmentCli \
  installDevelopmentShell \
  configureDevelopmentMachineDefaults \
  installDevelopmentIdeaPlugin \
  installDevelopmentLocal; do
  if grep -Eq "^${retired}[[:space:]]+-" <<<"$tasks"; then
    die "retired partial development task returned: ${retired}"
  fi
done

if grep -Fq 'KAST_BIN_DIR' "${repo_root}/build.gradle.kts"; then
  die 'local development must not install another command into a global bin directory'
fi

for owner in \
  "${repo_root}/cli-rs/src/local_development" \
  "${repo_root}/docs/distribute/local-development-refresh.md" \
  "${repo_root}/.github/scripts/test-local-development-refresh-contract.sh"; do
  if grep -R -Fq -- 'kast-dev' "$owner"; then
    die "local authority still exposes the retired command name under ${owner}"
  fi
done

printf '%s\n' 'Local development clean-break contract passed'
