#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
subject="${repo_root}/scripts/kast-build-environment.sh"
scratch="${repo_root}/.agent/runs/reusable-build-environment/test"
environment_home="${scratch}/environment"
shared_cargo_home="${scratch}/shared/cargo"
shared_rustup_home="${scratch}/shared/rustup"
shared_gradle_home="${scratch}/shared/gradle"
other_worktree="${scratch}/live-worktree"

rm -rf -- "$scratch"
mkdir -p "$shared_cargo_home" "$shared_rustup_home" "$shared_gradle_home"
mkdir -p "$other_worktree/cli-rs"
git -C "$other_worktree" init -q
: >"$other_worktree/settings.gradle.kts"
: >"$other_worktree/cli-rs/Cargo.toml"
trap 'rm -rf -- "$scratch"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_equal() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  [ "$actual" = "$expected" ] || fail "$label: expected '$expected', got '$actual'"
}

assert_not_equal() {
  local left="$1"
  local right="$2"
  local label="$3"
  [ "$left" != "$right" ] || fail "$label: both values were '$left'"
}

assert_within() {
  local parent="$1"
  local child="$2"
  local label="$3"
  case "$child" in
    "$parent"/*) ;;
    *) fail "$label: '$child' is not contained by '$parent'" ;;
  esac
}

capture_environment() {
  local workspace_root="$1"
  KAST_BUILD_ENV_HOME="$environment_home" \
    CARGO_HOME="$shared_cargo_home" \
    RUSTUP_HOME="$shared_rustup_home" \
    GRADLE_USER_HOME="$shared_gradle_home" \
    bash -c '
      set -euo pipefail
      source "$1" --workspace-root "$2"
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
        "$KAST_BUILD_WORKSPACE_HOME" \
        "$CARGO_TARGET_DIR" \
        "$KAST_BUILD_ROOT" \
        "$KAST_HOME" \
        "$KAST_CONFIG_HOME" \
        "$KAST_CACHE_HOME" \
        "$CARGO_HOME" \
        "$RUSTUP_HOME" \
        "$GRADLE_USER_HOME"
    ' bash "$subject" "$workspace_root"
}

[ -f "$subject" ] || fail "missing reusable build environment script"
[ -x "$subject" ] || fail "reusable build environment script is not executable"
[ -d "$other_worktree" ] || fail "expected comparison worktree at $other_worktree"

IFS=$'\t' read -r workspace_home cargo_target gradle_build kast_home kast_config kast_cache cargo_home rustup_home gradle_home \
  <<<"$(capture_environment "$repo_root")"
IFS=$'\t' read -r repeated_workspace_home repeated_cargo_target repeated_gradle_build _ \
  <<<"$(capture_environment "$repo_root")"
IFS=$'\t' read -r other_workspace_home other_cargo_target other_gradle_build _ \
  <<<"$(capture_environment "$other_worktree")"

assert_equal "$workspace_home" "$repeated_workspace_home" "same worktree capsule"
assert_equal "$cargo_target" "$repeated_cargo_target" "same worktree Cargo target"
assert_equal "$gradle_build" "$repeated_gradle_build" "same worktree Gradle output"
assert_not_equal "$workspace_home" "$other_workspace_home" "distinct worktree capsules"
assert_not_equal "$cargo_target" "$other_cargo_target" "distinct Cargo targets"
assert_not_equal "$gradle_build" "$other_gradle_build" "distinct Gradle outputs"

assert_equal "$shared_cargo_home" "$cargo_home" "shared Cargo dependency home"
assert_equal "$shared_rustup_home" "$rustup_home" "shared Rust toolchain home"
assert_equal "$shared_gradle_home" "$gradle_home" "shared Gradle dependency home"
assert_within "$environment_home/workspaces" "$workspace_home" "workspace capsule"
assert_within "$workspace_home" "$cargo_target" "Cargo target"
assert_within "$workspace_home" "$gradle_build" "Gradle build root"
assert_within "$workspace_home" "$kast_home" "Kast installation state"
assert_within "$workspace_home" "$kast_config" "Kast configuration state"
assert_within "$workspace_home" "$kast_cache" "Kast cache state"

orphan_root="${scratch}/orphan-worktree"
mkdir -p "$orphan_root/cli-rs"
git -C "$orphan_root" init -q
: >"$orphan_root/settings.gradle.kts"
: >"$orphan_root/cli-rs/Cargo.toml"
IFS=$'\t' read -r orphan_workspace_home _ <<<"$(capture_environment "$orphan_root")"
[ -d "$orphan_workspace_home" ] || fail "orphan fixture capsule was not created"
rm -rf -- "$orphan_root"
unowned_neighbor="${environment_home}/workspaces/not-a-capsule"
mkdir -p "$unowned_neighbor"
printf '%s\n' "${scratch}/missing-unowned-root" >"${unowned_neighbor}/workspace-root"
capture_environment "$repo_root" >/dev/null
[ ! -e "$orphan_workspace_home" ] || fail "orphaned worktree capsule was not pruned"
[ -d "$unowned_neighbor" ] || fail "unowned neighboring directory was pruned"
[ -d "$workspace_home" ] || fail "live worktree capsule was pruned"
[ -d "$other_workspace_home" ] || fail "comparison worktree capsule was pruned"

rg -q 'environmentVariable\("KAST_BUILD_ROOT"\)' "$repo_root/build.gradle.kts" \
  || fail "Gradle does not read the contained build root"
rg -q 'layout\.buildDirectory\.set' "$repo_root/build.gradle.kts" \
  || fail "Gradle does not relocate build directories"
rg -q 'environmentVariable\("CARGO_TARGET_DIR"\)' "$repo_root/build.gradle.kts" \
  || fail "Gradle development tasks do not honor the contained Cargo target"

printf 'PASS: reusable exact-worktree build environment contract\n'
