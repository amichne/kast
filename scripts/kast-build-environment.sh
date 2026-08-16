#!/usr/bin/env bash

_kast_build_environment_usage() {
  cat <<'USAGE'
Usage:
  source scripts/kast-build-environment.sh [--workspace-root <dir>]
  scripts/kast-build-environment.sh [--workspace-root <dir>] -- <command> [args...]
  scripts/kast-build-environment.sh [--workspace-root <dir>] --print

Activates one stable build capsule for the exact Git worktree. Mutable Cargo,
Gradle build, Kast runtime, configuration, cache, and JetBrains state is kept
outside the checkout. Existing Cargo, Rustup, and Gradle homes remain shared so
downloaded dependencies, toolchains, and Gradle's build cache are reused.

An activation removes only capsules whose recorded worktree directory no
longer exists. Run kast_build_environment_deactivate after sourcing to restore
the previous shell environment. Command mode retains the capsule for reuse.

Options:
  --workspace-root <dir>  Select an exact Kast Git worktree. Defaults to the
                          worktree containing the current directory.
  --print                 Print the selected roots without starting a command.
  -h, --help              Show this help.
USAGE
}

_kast_build_environment_is_sourced() {
  if [ -n "${ZSH_EVAL_CONTEXT:-}" ]; then
    case "$ZSH_EVAL_CONTEXT" in
      *:file|*:file:*) return 0 ;;
    esac
  fi
  if [ -n "${BASH_VERSION:-}" ]; then
    [ "${BASH_SOURCE[0]}" != "$0" ]
    return
  fi
  return 1
}

_kast_build_environment_existing_dir() {
  (cd -- "$1" >/dev/null 2>&1 && pwd -P)
}

_kast_build_environment_create_dir() {
  mkdir -p -- "$1" || return 1
  _kast_build_environment_existing_dir "$1"
}

_kast_build_environment_workspace_root() {
  local requested candidate git_root
  requested="$1"
  candidate="${requested:-$(pwd -P)}"
  candidate="$(_kast_build_environment_existing_dir "$candidate")" || {
    printf 'error: workspace directory does not exist: %s\n' "$candidate" >&2
    return 1
  }
  git_root="$(git -C "$candidate" rev-parse --show-toplevel 2>/dev/null)" || {
    printf 'error: workspace is not a Git worktree: %s\n' "$candidate" >&2
    return 1
  }
  git_root="$(_kast_build_environment_existing_dir "$git_root")" || return 1
  if [ -n "$requested" ] && [ "$candidate" != "$git_root" ]; then
    printf 'error: --workspace-root must name the exact Git root: %s\n' "$git_root" >&2
    return 1
  fi
  if [ ! -f "$git_root/settings.gradle.kts" ] || [ ! -f "$git_root/cli-rs/Cargo.toml" ]; then
    printf 'error: workspace is not a Kast checkout: %s\n' "$git_root" >&2
    return 1
  fi
  printf '%s\n' "$git_root"
}

_kast_build_environment_workspace_id() {
  printf '%s\000' "$1" | git hash-object --stdin
}

_kast_build_environment_prune_orphans() {
  local environment_home workspaces capsule capsule_name manifest recorded_root expected_id
  if [ -n "${ZSH_VERSION:-}" ]; then
    setopt local_options nonomatch
  fi
  environment_home="$1"
  workspaces="${environment_home}/workspaces"
  mkdir -p -- "$workspaces" || return 1
  for capsule in "$workspaces"/*; do
    [ -d "$capsule" ] || continue
    [ ! -L "$capsule" ] || continue
    capsule_name="${capsule##*/}"
    [ "${#capsule_name}" -eq 40 ] || continue
    case "$capsule_name" in
      *[!0-9a-f]*) continue ;;
    esac
    manifest="${capsule}/workspace-root"
    [ -f "$manifest" ] || continue
    [ ! -L "$manifest" ] || continue
    recorded_root=""
    IFS= read -r recorded_root <"$manifest" || continue
    [ -n "$recorded_root" ] || continue
    expected_id="$(_kast_build_environment_workspace_id "$recorded_root")" || continue
    [ "$capsule_name" = "$expected_id" ] || continue
    if [ ! -d "$recorded_root" ]; then
      rm -rf -- "$capsule"
    fi
  done
}

_kast_build_environment_save_var() {
  local name
  name="$1"
  eval '
    if [ "${'"$name"'+x}" ]; then
      export KAST_BUILD_ENV_OLD_'"$name"'="${'"$name"'}"
      export KAST_BUILD_ENV_OLD_'"$name"'_SET=1
    else
      unset KAST_BUILD_ENV_OLD_'"$name"'
      export KAST_BUILD_ENV_OLD_'"$name"'_SET=0
    fi
  '
}

_kast_build_environment_restore_var() {
  local name old_value old_set
  name="$1"
  eval 'old_set="${KAST_BUILD_ENV_OLD_'"$name"'_SET:-0}"'
  if [ "$old_set" = 1 ]; then
    eval 'old_value="${KAST_BUILD_ENV_OLD_'"$name"'}"'
    export "$name=$old_value"
  else
    unset "$name"
  fi
  unset "KAST_BUILD_ENV_OLD_${name}" "KAST_BUILD_ENV_OLD_${name}_SET"
}

kast_build_environment_deactivate() {
  _kast_build_environment_restore_var KAST_BUILD_ENV_ACTIVE
  _kast_build_environment_restore_var KAST_BUILD_ENV_HOME
  _kast_build_environment_restore_var KAST_BUILD_WORKSPACE_ROOT
  _kast_build_environment_restore_var KAST_BUILD_WORKSPACE_HOME
  _kast_build_environment_restore_var KAST_BUILD_ROOT
  _kast_build_environment_restore_var KAST_HOME
  _kast_build_environment_restore_var KAST_CONFIG_HOME
  _kast_build_environment_restore_var KAST_CACHE_HOME
  _kast_build_environment_restore_var KAST_JETBRAINS_CONFIG_ROOT
  _kast_build_environment_restore_var KAST_WORKSPACE_ID
  _kast_build_environment_restore_var KAST_BIN_DIR
  _kast_build_environment_restore_var CARGO_TARGET_DIR
  _kast_build_environment_restore_var PATH
  printf 'Kast build environment deactivated.\n'
}

_kast_build_environment_activate() {
  local workspace_root environment_home workspace_id workspace_home manifest_tmp variable
  workspace_root="$1"
  if [ "${KAST_BUILD_ENV_ACTIVE:-}" = 1 ]; then
    printf 'error: a Kast build environment is already active\n' >&2
    return 1
  fi

  environment_home="${KAST_BUILD_ENV_HOME:-${XDG_CACHE_HOME:-${HOME:?HOME must be set}/.cache}/kast/build-environments}"
  environment_home="$(_kast_build_environment_create_dir "$environment_home")" || return 1
  _kast_build_environment_prune_orphans "$environment_home" || return 1

  workspace_id="$(_kast_build_environment_workspace_id "$workspace_root")" || return 1
  workspace_home="${environment_home}/workspaces/${workspace_id}"
  workspace_home="$(_kast_build_environment_create_dir "$workspace_home")" || return 1

  for variable in \
    KAST_BUILD_ENV_ACTIVE \
    KAST_BUILD_ENV_HOME \
    KAST_BUILD_WORKSPACE_ROOT \
    KAST_BUILD_WORKSPACE_HOME \
    KAST_BUILD_ROOT \
    KAST_HOME \
    KAST_CONFIG_HOME \
    KAST_CACHE_HOME \
    KAST_JETBRAINS_CONFIG_ROOT \
    KAST_WORKSPACE_ID \
    KAST_BIN_DIR \
    CARGO_TARGET_DIR \
    PATH
  do
    _kast_build_environment_save_var "$variable"
  done

  export KAST_BUILD_ENV_ACTIVE=1
  export KAST_BUILD_ENV_HOME="$environment_home"
  export KAST_BUILD_WORKSPACE_ROOT="$workspace_root"
  export KAST_BUILD_WORKSPACE_HOME="$workspace_home"
  export KAST_BUILD_ROOT="${workspace_home}/build"
  export KAST_HOME="${workspace_home}/kast-home"
  export KAST_CONFIG_HOME="${workspace_home}/config"
  export KAST_CACHE_HOME="${workspace_home}/cache"
  export KAST_JETBRAINS_CONFIG_ROOT="${workspace_home}/jetbrains"
  export KAST_WORKSPACE_ID="$workspace_id"
  export KAST_BIN_DIR="${workspace_home}/bin"
  export CARGO_TARGET_DIR="${KAST_BUILD_ROOT}/cargo"
  export PATH="${KAST_BIN_DIR}:${PATH}"

  mkdir -p -- \
    "$KAST_BUILD_ROOT" \
    "$KAST_HOME" \
    "$KAST_CONFIG_HOME" \
    "$KAST_CACHE_HOME" \
    "$KAST_JETBRAINS_CONFIG_ROOT" \
    "$KAST_BIN_DIR" \
    "$CARGO_TARGET_DIR" || return 1

  manifest_tmp="${workspace_home}/.workspace-root.$$"
  printf '%s\n' "$workspace_root" >"$manifest_tmp" || return 1
  mv -f -- "$manifest_tmp" "${workspace_home}/workspace-root" || return 1
  touch "${workspace_home}/last-used" || return 1
}

_kast_build_environment_print() {
  printf 'KAST_BUILD_WORKSPACE_ROOT=%s\n' "$KAST_BUILD_WORKSPACE_ROOT"
  printf 'KAST_BUILD_WORKSPACE_HOME=%s\n' "$KAST_BUILD_WORKSPACE_HOME"
  printf 'KAST_BUILD_ROOT=%s\n' "$KAST_BUILD_ROOT"
  printf 'CARGO_TARGET_DIR=%s\n' "$CARGO_TARGET_DIR"
  printf 'KAST_HOME=%s\n' "$KAST_HOME"
  printf 'KAST_CONFIG_HOME=%s\n' "$KAST_CONFIG_HOME"
  printf 'KAST_CACHE_HOME=%s\n' "$KAST_CACHE_HOME"
}

_kast_build_environment_main() {
  local requested_root mode workspace_root
  requested_root=""
  mode=activate

  while [ "$#" -gt 0 ]; do
    case "$1" in
      --workspace-root)
        shift
        [ "$#" -gt 0 ] || {
          printf 'error: --workspace-root requires a directory\n' >&2
          return 1
        }
        requested_root="$1"
        ;;
      --print)
        mode=print
        ;;
      --)
        shift
        mode=command
        break
        ;;
      -h|--help)
        _kast_build_environment_usage
        return 0
        ;;
      *)
        printf 'error: unknown argument: %s\n' "$1" >&2
        _kast_build_environment_usage >&2
        return 1
        ;;
    esac
    shift
  done

  if _kast_build_environment_is_sourced && [ "$mode" != activate ]; then
    printf 'error: --print and command mode must execute the script instead of sourcing it\n' >&2
    return 1
  fi
  if ! _kast_build_environment_is_sourced && [ "$mode" = activate ]; then
    _kast_build_environment_usage >&2
    printf '\nerror: source the script, pass --print, or pass -- <command>\n' >&2
    return 1
  fi
  if [ "$mode" = command ] && [ "$#" -eq 0 ]; then
    printf 'error: -- requires a command\n' >&2
    return 1
  fi

  workspace_root="$(_kast_build_environment_workspace_root "$requested_root")" || return 1
  _kast_build_environment_activate "$workspace_root" || return 1

  case "$mode" in
    print)
      _kast_build_environment_print
      ;;
    command)
      "$@"
      ;;
    activate)
      ;;
  esac
}

_kast_build_environment_main "$@"
_kast_build_environment_status=$?
if _kast_build_environment_is_sourced; then
  return "$_kast_build_environment_status"
else
  exit "$_kast_build_environment_status"
fi
