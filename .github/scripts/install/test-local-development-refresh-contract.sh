#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"

[[ ! -e "$repo_root/cli-rs/src/local_development.rs" ]]
[[ ! -d "$repo_root/cli-rs/src/local_development" ]]

help="$($repo_root/gradlew -q help --task refreshDevelopmentMachine)"
grep -Fq 'Replaces the active installation through the sole setup transaction.' <<<"$help"

dry_run="$($repo_root/gradlew -m refreshDevelopmentMachine --no-daemon)"
clean_dry_run="$($repo_root/gradlew -m -PkastDevelopmentClean=true refreshDevelopmentMachine --no-daemon)"
for task in \
  ':buildDevelopmentCli' \
  ':stageDevelopmentControlCli' \
  ':packageDevelopmentCli' \
  ':backend-headless:portableDistZip' \
  ':backend-idea:buildPlugin' \
  ':packageDevelopmentSetupBundle' \
  ':refreshDevelopmentMachine'; do
  grep -Fq "$task" <<<"$dry_run" || { printf 'error: missing development setup task %s\n' "$task" >&2; exit 1; }
  grep -Fq "$task" <<<"$clean_dry_run" || { printf 'error: clean development setup skipped task %s\n' "$task" >&2; exit 1; }
done

for unwanted in \
  ':activateDevelopmentMachine' \
  ':reconcileDevelopmentMachine'; do
  ! grep -Fq "$unwanted" <<<"$dry_run" || { printf 'error: unwanted development setup task remains: %s\n' "$unwanted" >&2; exit 1; }
done

"$repo_root/gradlew" -q packageDevelopmentCli --no-daemon --console=plain
cli_archive_entries="$(unzip -Z1 "$repo_root/build/setup/kast-cli.zip")"
[[ "$cli_archive_entries" == $'kast\nkastctl' ]] || {
  printf 'error: development CLI archive must contain root kast and kastctl, found: %s\n' "$cli_archive_entries" >&2
  exit 1
}

refresh_task="$(sed -n '/tasks.register<Exec>("refreshDevelopmentMachine")/,/^}/p' "$repo_root/build.gradle.kts")"
grep -Fq '"setup",' <<<"$refresh_task"
grep -Fq '"--source",' <<<"$refresh_task"
grep -Fq 'kastDevelopmentClean' "$repo_root/build.gradle.kts"
grep -Fq 'args("--force")' <<<"$refresh_task"
! grep -Fq '"--idea-plugin",' <<<"$refresh_task"
! grep -Fq 'dependsOn("clean")' <<<"$refresh_task"

printf '%s\n' 'local setup refresh contract passed'
