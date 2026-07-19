#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

repo_root="$(resolve_repo_root)"
task_list="$("${repo_root}/gradlew" -q tasks --all)"
for retired in \
  refreshDevelopmentLocal \
  prepareDevelopmentLocalGeneration \
  activateDevelopmentLocal \
  rollbackDevelopmentLocal \
  removeDevelopmentLocal \
  installDevelopmentLocal \
  localHeadlessPluginImplementationJar; do
  if grep -Fq "$retired" <<<"$task_list"; then
    die "retired developer-machine authority is still exposed: ${retired}"
  fi
done

machine_help="$("${repo_root}/gradlew" -q help --task refreshDevelopmentMachine)"
grep -Fq 'Refreshes one processless machine bundle from the current checkout.' <<<"$machine_help" \
  || die 'refreshDevelopmentMachine must describe the processless machine boundary'

dry_run="$("${repo_root}/gradlew" -m refreshDevelopmentMachine --no-daemon)"
for required in \
  ':buildDevelopmentCli' \
  ':backend-idea:buildPlugin' \
  ':activateDevelopmentMachine' \
  ':reconcileDevelopmentMachine' \
  ':refreshDevelopmentMachine'; do
  grep -Fq "$required" <<<"$dry_run" \
    || die "refreshDevelopmentMachine must include ${required}"
done
if grep -Fq ':backend-headless:' <<<"$dry_run"; then
  die 'developer-machine refresh must not build or start the headless backend'
fi

developer_help="$(
  cargo run \
    --quiet \
    --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
    --locked \
    --bin kast \
    -- \
    developer --help
)"
if grep -Eq '^  local([[:space:]]|$)' <<<"$developer_help"; then
  die 'developer local must not remain a public command'
fi

bash -n "${BASH_SOURCE[0]}"
printf '%s\n' 'processless development machine contract passed'
