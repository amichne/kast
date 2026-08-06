#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'Git hook installation: %s\n' "$*" >&2
  exit 1
}

is_managed_hook_directory() {
  local candidate_dir="$1"
  [[ -x "${candidate_dir}/pre-push" ]] || return 1
  cmp -s "$hook" "${candidate_dir}/pre-push" || return 1
  for candidate_hook in "${candidate_dir}"/*; do
    [[ -e "$candidate_hook" || -L "$candidate_hook" ]] || continue
    [[ "${candidate_hook##*/}" == *.sample ]] && continue
    [[ "${candidate_hook##*/}" == pre-push ]] && continue
    [[ -x "$candidate_hook" ]] && return 1
  done
  return 0
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
managed_hooks_path="${repo_root}/.githooks"
hook="${managed_hooks_path}/pre-push"
[[ -x "$hook" ]] || die "managed pre-push hook is unavailable: ${hook}"

current_hooks_path="$(git -C "$repo_root" config --get core.hooksPath || true)"
case "$current_hooks_path" in
  '')
    default_hooks_dir="$(
      git -C "$repo_root" rev-parse --path-format=absolute --git-path hooks
    )"
    for default_hook in "${default_hooks_dir}"/*; do
      [[ -e "$default_hook" || -L "$default_hook" ]] || continue
      [[ "${default_hook##*/}" == *.sample ]] && continue
      [[ -x "$default_hook" ]] || continue
      die "refusing to shadow unmanaged Git hook: ${default_hook}"
    done
    ;;
  '.githooks'|"$managed_hooks_path") ;;
  *)
    current_managed_path="$current_hooks_path"
    if [[ "$current_managed_path" != /* ]]; then
      current_managed_path="${repo_root}/${current_managed_path}"
    fi
    is_managed_hook_directory "$current_managed_path" \
      || die "refusing to replace unmanaged core.hooksPath: ${current_hooks_path}"
    ;;
esac

for required_command in cargo git python3; do
  command -v "$required_command" >/dev/null 2>&1 \
    || die "required command is unavailable: ${required_command}"
done
cargo deny --version >/dev/null 2>&1 \
  || die 'cargo-deny is unavailable; run: cargo install cargo-deny --locked'
cargo fmt --version >/dev/null 2>&1 \
  || die 'Rustfmt is unavailable; run: rustup component add rustfmt'
cargo clippy --version >/dev/null 2>&1 \
  || die 'Clippy is unavailable; run: rustup component add clippy'

git -C "$repo_root" config --local extensions.worktreeConfig true
git -C "$repo_root" config --worktree core.hooksPath "$managed_hooks_path"
printf 'Git hook installation: pre-push checks enabled for %s\n' "$repo_root"
