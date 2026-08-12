#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'Pre-push check: %s\n' "$*" >&2
  exit 1
}

run_check() {
  local label="$1"
  shift
  printf 'Pre-push check: %s\n' "$label" >&2
  "$@"
}

is_zero_oid() {
  [[ "$1" =~ ^0+$ ]]
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

for required_command in cargo git python3; do
  command -v "$required_command" >/dev/null 2>&1 \
    || die "required command is unavailable: ${required_command}"
done

if [[ "${1:-}" == '--all' ]]; then
  [[ "$#" -eq 1 ]] || die 'usage: scripts/pre-push-check.sh --all'
  run_check 'checking unstaged whitespace' git diff --check
  run_check 'checking staged whitespace' git diff --cached --check
else
  [[ "$#" -eq 2 ]] \
    || die 'the Git pre-push hook must supply the remote name and URL'
  remote_name="$1"
  head_commit="$(git rev-parse --verify 'HEAD^{commit}' 2>/dev/null)" \
    || die 'the current HEAD is not a commit'
  saw_update=false
  while read -r local_ref local_oid remote_ref remote_oid extra; do
    [[ -z "${extra:-}" ]] || die 'Git supplied a malformed pre-push update'
    [[ -n "${remote_oid:-}" ]] || die 'Git supplied an incomplete pre-push update'
    if is_zero_oid "$local_oid"; then
      continue
    fi
    saw_update=true
    local_commit="$(git rev-parse --verify "${local_oid}^{commit}" 2>/dev/null)" \
      || die "local push object is not a commit: ${local_ref}"
    [[ "$local_commit" == "$head_commit" ]] \
      || die "refusing to lint a pushed commit that is not current HEAD: ${local_ref}"
    if is_zero_oid "$remote_oid"; then
      remote_default="$(git symbolic-ref --quiet "refs/remotes/${remote_name}/HEAD" 2>/dev/null || true)"
      if [[ -z "$remote_default" ]] \
        && git show-ref --verify --quiet "refs/remotes/${remote_name}/main"; then
        remote_default="refs/remotes/${remote_name}/main"
      fi
      [[ -n "$remote_default" ]] \
        || die "cannot determine the default branch for new remote ref: ${remote_ref}"
      base_commit="$(git merge-base "$local_commit" "$remote_default")" \
        || die "new remote ref has no merge base with ${remote_default}: ${remote_ref}"
    else
      base_commit="$(git rev-parse --verify "${remote_oid}^{commit}" 2>/dev/null)" \
        || die "remote push object is not a commit: ${remote_ref}"
    fi
    run_check "checking pushed whitespace for ${local_ref}" \
      git diff --check "$base_commit" "$local_commit"
  done
  if [[ "$saw_update" == false ]]; then
    printf '%s\n' 'Pre-push check: no non-deletion updates to validate' >&2
    exit 0
  fi
  worktree_status="$(git status --porcelain --untracked-files=all)" \
    || die 'Git could not inspect the worktree before linting'
  [[ -z "$worktree_status" ]] \
    || die 'the worktree must be clean so checks match the pushed commit'
fi

cargo deny --version >/dev/null 2>&1 \
  || die 'cargo-deny is unavailable; run: cargo install cargo-deny --locked'
cargo fmt --version >/dev/null 2>&1 \
  || die 'Rustfmt is unavailable; run: rustup component add rustfmt'
cargo clippy --version >/dev/null 2>&1 \
  || die 'Clippy is unavailable; run: rustup component add clippy'

run_check 'testing repository shape policy' \
  bash .github/scripts/test-repository-shape-contract.sh
run_check 'checking repository shape' \
  python3 .github/scripts/check-repository-shape.py --root .
run_check 'checking Kast architecture' \
  ./gradlew verifyKastArchitecture --configuration-cache
run_check 'testing Rust tooling policy' \
  ./.github/scripts/ci/test-rust-agent-tooling-contract.sh
run_check 'checking Rust dependency policy' \
  cargo deny --manifest-path cli-rs/Cargo.toml --locked --all-features \
  --config cli-rs/.config/deny.toml check
run_check 'checking Rust formatting' \
  cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
run_check 'checking Rust lints' \
  cargo clippy --manifest-path cli-rs/Cargo.toml \
  --locked --all-targets --all-features -- -D warnings

printf '%s\n' 'Pre-push check passed' >&2
