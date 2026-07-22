#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

cargo test \
  --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
  --locked \
  --test setup_smoke

grep -Fqx 'graphify-out/' "${repo_root}/.gitignore"
grep -Fqx 'cli-rs/graphify-out/' "${repo_root}/.gitignore"

if sed '/^[[:space:]]*if brew install fzf; then$/d' "${repo_root}/install.sh" |
  grep -Eq '\bbrew (tap|install|update|upgrade|reinstall)\b'; then
  printf '%s\n' 'error: bootstrap still mutates Homebrew state' >&2
  exit 1
fi
grep -Fq 'setup --source' "${repo_root}/install.sh"
grep -Fq 'setup' "${repo_root}/build.gradle.kts"

test ! -e "${repo_root}/scripts/install-ubuntu-debian.sh"
test ! -e "${repo_root}/packaging/homebrew"

printf '%s\n' 'sole setup contract passed'
