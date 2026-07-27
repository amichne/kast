#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"

[[ ! -e "$repo_root/cli-rs/src/local_development.rs" ]]
[[ ! -d "$repo_root/cli-rs/src/local_development" ]]

help="$($repo_root/gradlew -q help --task refreshDevelopmentMachine)"
grep -Fq 'Replaces the active installation through the sole setup transaction.' <<<"$help"

dry_run="$($repo_root/gradlew -m refreshDevelopmentMachine --no-daemon)"
for task in \
  ':buildDevelopmentCli' \
  ':backend-idea:buildPlugin' \
  ':refreshDevelopmentMachine'; do
  grep -Fq "$task" <<<"$dry_run" || { printf 'error: missing development setup task %s\n' "$task" >&2; exit 1; }
done

for unwanted in \
  ':packageDevelopmentCli' \
  ':backend-headless:portableDistZip' \
  ':packageDevelopmentSetupBundle' \
  ':activateDevelopmentMachine' \
  ':reconcileDevelopmentMachine'; do
  ! grep -Fq "$unwanted" <<<"$dry_run" || { printf 'error: unwanted development setup task remains: %s\n' "$unwanted" >&2; exit 1; }
done

refresh_task="$(sed -n '/tasks.register<Exec>("refreshDevelopmentMachine")/,/^}/p' "$repo_root/build.gradle.kts")"
grep -Fq '"setup",' <<<"$refresh_task"
grep -Fq '"--idea-plugin",' <<<"$refresh_task"
! grep -Fq '"--source",' <<<"$refresh_task"

printf '%s\n' 'local setup refresh contract passed'
