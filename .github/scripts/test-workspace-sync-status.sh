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

require_contains() {
  local content="$1"
  local expected="$2"
  local description="$3"

  grep -Fq -- "$expected" <<<"$content" || die "${description}: missing '${expected}'"
}

create_git_repo() {
  local repo_path="$1"
  local remote_url="$2"

  git init -q -b main "$repo_path"
  git -C "$repo_path" config user.email "test@example.invalid"
  git -C "$repo_path" config user.name "Kast Test"
  printf '%s\n' "fixture" > "${repo_path}/README.md"
  git -C "$repo_path" add README.md
  git -C "$repo_path" commit -q -m "test fixture"
  git -C "$repo_path" remote add origin "$remote_url"
}

repo_root="$(resolve_repo_root)"
status_script="${repo_root}/scripts/workspace-sync-status.sh"
manifest="${repo_root}/workspace.repos.toml"

[[ -f "$manifest" ]] || die "workspace manifest not found: ${manifest}"
[[ -x "$status_script" ]] || die "workspace status script not found or not executable: ${status_script}"

manifest_content="$(<"$manifest")"
require_contains "$manifest_content" "[repos.kast]" "Manifest must declare the kast repo"
require_contains "$manifest_content" "[repos.kast-rs]" "Manifest must declare the Rust CLI repo"
require_contains "$manifest_content" "[repos.homebrew-kast]" "Manifest must declare the Homebrew tap repo"
require_contains "$manifest_content" 'path = "../kast-rs"' "Rust CLI must be modeled as a sibling checkout"
require_contains "$manifest_content" 'path = "../homebrew-kast"' "Homebrew tap must be modeled as a sibling checkout"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

workspace_root="${tmp_dir}/workspace"
mkdir -p "${workspace_root}/kast" "${workspace_root}/kast-rs"

cat > "${workspace_root}/kast/workspace.repos.toml" <<'TOML'
[repos.kast]
path = "."
remote = "git@github.com:amichne/kast.git"
branch = "main"
role = "JVM daemon and contract"

[repos.kast-rs]
path = "../kast-rs"
remote = "git@github.com:amichne/kast-rs.git"
branch = "main"
role = "Rust CLI"

[repos.homebrew-kast]
path = "../homebrew-kast"
remote = "git@github.com:amichne/homebrew-kast.git"
branch = "main"
role = "Homebrew tap"
TOML

create_git_repo "${workspace_root}/kast" "git@github.com:amichne/kast.git"
create_git_repo "${workspace_root}/kast-rs" "git@github.com:amichne/kast-rs.git"

output="$("$status_script" --manifest "${workspace_root}/kast/workspace.repos.toml")"

require_contains "$output" "Workspace repos" "Status output must identify the report"
require_contains "$output" "kast" "Status output must include the current repo"
require_contains "$output" "kast-rs" "Status output must include the Rust CLI repo"
require_contains "$output" "homebrew-kast" "Status output must include the Homebrew tap repo"
require_contains "$output" "present" "Status output must report present repositories"
require_contains "$output" "missing" "Status output must report missing repositories"
require_contains "$output" "git@github.com:amichne/kast-rs.git" "Status output must show the expected remote"

if "$status_script" --manifest "${workspace_root}/kast/workspace.repos.toml" --strict >/dev/null 2>&1; then
  die "Strict status must fail when a manifest repository is missing"
fi

printf '%s\n' "Workspace sync status contract passed"
