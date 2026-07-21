#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

[[ ! -e "$repo_root/cli-rs/src/local_development.rs" ]]
[[ ! -d "$repo_root/cli-rs/src/local_development" ]]

help="$($repo_root/gradlew -q help --task refreshDevelopmentMachine)"
grep -Fq 'Replaces the active installation through the sole setup transaction.' <<<"$help"

dry_run="$($repo_root/gradlew -m refreshDevelopmentMachine --no-daemon)"
for task in \
  ':buildDevelopmentCli' \
  ':packageDevelopmentCli' \
  ':backend-headless:portableDistZip' \
  ':backend-idea:buildPlugin' \
  ':packageDevelopmentSetupBundle' \
  ':refreshDevelopmentMachine'; do
  grep -Fq "$task" <<<"$dry_run" || { printf 'error: missing development setup task %s\n' "$task" >&2; exit 1; }
done

for retired in ':activateDevelopmentMachine' ':reconcileDevelopmentMachine'; do
  ! grep -Fq "$retired" <<<"$dry_run" || { printf 'error: retired task remains: %s\n' "$retired" >&2; exit 1; }
done

grep -Fq '"setup",' "$repo_root/build.gradle.kts"
grep -Fq '"--source",' "$repo_root/build.gradle.kts"

printf '%s\n' 'local setup refresh contract passed'
