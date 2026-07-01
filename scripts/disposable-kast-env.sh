#!/usr/bin/env bash

_kast_disposable_usage() {
  cat <<'USAGE'
Usage:
  source scripts/disposable-kast-env.sh [options]
  scripts/disposable-kast-env.sh [options] -- <command> [args...]
  scripts/disposable-kast-env.sh [options] --shell

Creates an isolated local Kast install for the current shell or a single
command. The environment shadows any host Kast binary and redirects Kast
install, config, cache, runtime, and JetBrains profile state under one
disposable root.

Options:
  --root <dir>            Reuse or create a specific disposable root.
  --idea-plugin           Install the development IDEA plugin into the
                          disposable JetBrains profile with the real
                          installDevelopmentIdeaPlugin Gradle task.
  --setup-workspace [dir] Run agent setup for a workspace. Defaults to cwd
                          when dir is omitted.
  --verify                Run command-surface checks and package-verify when
                          --setup-workspace is also used.
  --no-ready-fix          Do not run isolated `kast ready --fix`.
  --keep                  In command or shell mode, keep the disposable root
                          after the command exits.
  --shell                 Start an interactive shell inside the isolated env.
  -h, --help              Show this help.

After sourcing, run `kast_disposable_deactivate` to restore the previous shell
environment. Command and shell modes clean up automatically unless --keep is
passed.
USAGE
}

_kast_disposable_is_sourced() {
  if [ -n "${ZSH_EVAL_CONTEXT:-}" ]; then
    case "$ZSH_EVAL_CONTEXT" in
      *:file) return 0 ;;
    esac
  fi
  if [ -n "${BASH_VERSION:-}" ]; then
    [ "${BASH_SOURCE[0]}" != "$0" ]
    return
  fi
  return 1
}

_kast_disposable_script_path() {
  if [ -n "${BASH_VERSION:-}" ]; then
    printf '%s\n' "${BASH_SOURCE[0]}"
  elif [ -n "${ZSH_VERSION:-}" ]; then
    eval 'printf "%s\n" "${(%):-%x}"'
  else
    printf '%s\n' "$0"
  fi
}

_kast_disposable_repo_root() {
  local script_path script_dir candidate
  script_path="$(_kast_disposable_script_path)"
  script_dir="$(cd -- "$(dirname -- "$script_path")" >/dev/null 2>&1 && pwd -P)" || return 1
  candidate="$(cd -- "${script_dir}/.." >/dev/null 2>&1 && pwd -P)" || return 1
  if [ -f "${candidate}/settings.gradle.kts" ] && [ -d "${candidate}/cli-rs" ]; then
    printf '%s\n' "$candidate"
    return 0
  fi
  git rev-parse --show-toplevel
}

_kast_disposable_abs_dir() {
  local path parent base
  path="$1"
  mkdir -p "$path" || return 1
  parent="$(cd -- "$(dirname -- "$path")" >/dev/null 2>&1 && pwd -P)" || return 1
  base="$(basename -- "$path")"
  printf '%s/%s\n' "$parent" "$base"
}

_kast_disposable_timestamp() {
  date +%Y%m%d%H%M%S 2>/dev/null || printf '%s\n' "$$"
}

_kast_disposable_kill_tree() {
  local root_pid signal child
  root_pid="$1"
  signal="$2"
  while IFS= read -r child; do
    [ -n "$child" ] || continue
    _kast_disposable_kill_tree "$child" "$signal"
  done <<EOF
$(pgrep -P "$root_pid" 2>/dev/null || true)
EOF
  kill "-${signal}" "$root_pid" 2>/dev/null || true
}

_kast_disposable_run_step() {
  local name expectation stdout_file stderr_file status_file command_file pid timed_out timeout_seconds deadline status
  name="$1"
  expectation="$2"
  shift 2

  stdout_file="${KAST_DISPOSABLE_ARTIFACTS}/${name}.stdout"
  stderr_file="${KAST_DISPOSABLE_ARTIFACTS}/${name}.stderr"
  status_file="${KAST_DISPOSABLE_ARTIFACTS}/${name}.status"
  command_file="${KAST_DISPOSABLE_ARTIFACTS}/${name}.command"

  printf '%s\n' "$*" >"$command_file"
  printf 'running %s\n' "$name"

  "$@" >"$stdout_file" 2>"$stderr_file" &
  pid=$!
  timed_out=false
  timeout_seconds="${KAST_DISPOSABLE_STEP_TIMEOUT_SECONDS:-900}"
  deadline=$((SECONDS + timeout_seconds))

  while kill -0 "$pid" 2>/dev/null; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      timed_out=true
      printf 'step %s timed out after %s seconds\n' "$name" "$timeout_seconds" >>"$stderr_file"
      _kast_disposable_kill_tree "$pid" TERM
      sleep 2
      if kill -0 "$pid" 2>/dev/null; then
        _kast_disposable_kill_tree "$pid" KILL
      fi
      break
    fi
    sleep 1
  done

  wait "$pid"
  status=$?
  if [ "$timed_out" = true ]; then
    status=124
  fi
  printf '%s\n' "$status" >"$status_file"

  if [ "$timed_out" = true ] || { [ "$expectation" = success ] && [ "$status" -ne 0 ]; }; then
    printf 'error: %s failed with exit code %s\n' "$name" "$status" >&2
    printf 'stdout: %s\n' "$stdout_file" >&2
    printf 'stderr: %s\n' "$stderr_file" >&2
    return 1
  fi
}

_kast_disposable_save_var() {
  local name
  name="$1"
  eval '
    if [ "${'"$name"'+x}" ]; then
      export KAST_DISPOSABLE_OLD_'"$name"'="${'"$name"'}"
      export KAST_DISPOSABLE_OLD_'"$name"'_SET=1
    else
      unset KAST_DISPOSABLE_OLD_'"$name"'
      export KAST_DISPOSABLE_OLD_'"$name"'_SET=0
    fi
  '
}

_kast_disposable_restore_var() {
  local name old_value old_set
  name="$1"
  eval 'old_set="${KAST_DISPOSABLE_OLD_'"$name"'_SET:-0}"'
  if [ "$old_set" = 1 ]; then
    eval 'old_value="${KAST_DISPOSABLE_OLD_'"$name"'}"'
    export "$name=$old_value"
  else
    unset "$name"
  fi
  unset "KAST_DISPOSABLE_OLD_${name}" "KAST_DISPOSABLE_OLD_${name}_SET"
}

kast_disposable_deactivate() {
  _kast_disposable_restore_var HOME
  _kast_disposable_restore_var KAST_INSTALL_ROOT
  _kast_disposable_restore_var KAST_CONFIG_HOME
  _kast_disposable_restore_var KAST_CACHE_HOME
  _kast_disposable_restore_var KAST_JETBRAINS_CONFIG_ROOT
  _kast_disposable_restore_var KAST_WORKSPACE_ID
  _kast_disposable_restore_var KAST_BIN_DIR
  _kast_disposable_restore_var GRADLE_USER_HOME
  _kast_disposable_restore_var CARGO_HOME
  _kast_disposable_restore_var RUSTUP_HOME
  _kast_disposable_restore_var PATH
  unset KAST_DISPOSABLE_ACTIVE KAST_DISPOSABLE_ROOT KAST_DISPOSABLE_ARTIFACTS
  unset -f kast_disposable_deactivate 2>/dev/null || true
  printf 'Kast disposable environment deactivated.\n'
}

_kast_disposable_activate_env() {
  local root old_home cargo_home rustup_home
  root="$1"
  old_home="${HOME:?HOME must be set}"
  cargo_home="${CARGO_HOME:-${old_home}/.cargo}"
  rustup_home="${RUSTUP_HOME:-${old_home}/.rustup}"

  _kast_disposable_save_var HOME
  _kast_disposable_save_var KAST_INSTALL_ROOT
  _kast_disposable_save_var KAST_CONFIG_HOME
  _kast_disposable_save_var KAST_CACHE_HOME
  _kast_disposable_save_var KAST_JETBRAINS_CONFIG_ROOT
  _kast_disposable_save_var KAST_WORKSPACE_ID
  _kast_disposable_save_var KAST_BIN_DIR
  _kast_disposable_save_var GRADLE_USER_HOME
  _kast_disposable_save_var CARGO_HOME
  _kast_disposable_save_var RUSTUP_HOME
  _kast_disposable_save_var PATH

  export KAST_DISPOSABLE_ACTIVE=1
  export KAST_DISPOSABLE_ROOT="$root"
  export KAST_DISPOSABLE_ARTIFACTS="${root}/artifacts"
  export HOME="${root}/home"
  export KAST_INSTALL_ROOT="${root}/install-root"
  export KAST_CONFIG_HOME="${root}/config"
  export KAST_CACHE_HOME="${root}/cache"
  export KAST_JETBRAINS_CONFIG_ROOT="${root}/jetbrains-config"
  export KAST_WORKSPACE_ID=disposable-kast
  export KAST_BIN_DIR="${HOME}/.local/bin"
  export GRADLE_USER_HOME="${KAST_DISPOSABLE_GRADLE_USER_HOME:-${root}/gradle-home}"
  export CARGO_HOME="$cargo_home"
  export RUSTUP_HOME="$rustup_home"
  export PATH="${KAST_BIN_DIR}:${PATH}"

  mkdir -p \
    "$HOME" \
    "$KAST_INSTALL_ROOT" \
    "$KAST_CONFIG_HOME" \
    "$KAST_CACHE_HOME" \
    "$KAST_JETBRAINS_CONFIG_ROOT" \
    "$KAST_BIN_DIR" \
    "$GRADLE_USER_HOME" \
    "$KAST_DISPOSABLE_ARTIFACTS" || return 1

  cat >"${KAST_DISPOSABLE_ARTIFACTS}/environment.txt" <<EOF
KAST_DISPOSABLE_ROOT=${KAST_DISPOSABLE_ROOT}
HOME=${HOME}
KAST_INSTALL_ROOT=${KAST_INSTALL_ROOT}
KAST_CONFIG_HOME=${KAST_CONFIG_HOME}
KAST_CACHE_HOME=${KAST_CACHE_HOME}
KAST_JETBRAINS_CONFIG_ROOT=${KAST_JETBRAINS_CONFIG_ROOT}
KAST_BIN_DIR=${KAST_BIN_DIR}
GRADLE_USER_HOME=${GRADLE_USER_HOME}
CARGO_HOME=${CARGO_HOME}
RUSTUP_HOME=${RUSTUP_HOME}
EOF
}

_kast_disposable_install_cli() {
  local repo_root
  repo_root="$1"
  _kast_disposable_run_step install-development-cli success \
    "$repo_root/gradlew" -q installDevelopmentCli || return 1
  if [ ! -x "${KAST_BIN_DIR}/kast-dev" ]; then
    printf 'error: expected executable development CLI at %s\n' "${KAST_BIN_DIR}/kast-dev" >&2
    return 1
  fi
  if [ -e "${KAST_BIN_DIR}/kast" ] && [ ! -L "${KAST_BIN_DIR}/kast" ]; then
    printf 'error: refusing to replace non-symlink %s\n' "${KAST_BIN_DIR}/kast" >&2
    return 1
  fi
  ln -sfn kast-dev "${KAST_BIN_DIR}/kast"
  command -v kast >"${KAST_DISPOSABLE_ARTIFACTS}/command-v-kast.stdout" || return 1
}

_kast_disposable_ready_fix() {
  _kast_disposable_run_step ready-fix success kast --output json ready --fix
}

_kast_disposable_install_idea_plugin() {
  local repo_root profile plugins
  repo_root="$1"
  profile="${KAST_JETBRAINS_CONFIG_ROOT}/IntelliJIdea2026.1"
  plugins="${profile}/plugins"
  mkdir -p "${plugins}" || return 1
  _kast_disposable_run_step install-development-idea-plugin success \
    "$repo_root/gradlew" -q installDevelopmentIdeaPlugin \
    -PkastDevJetBrainsConfigRoot="$KAST_JETBRAINS_CONFIG_ROOT" || return 1
}

_kast_disposable_verify_surface() {
  _kast_disposable_run_step kast-version success kast version || return 1
  _kast_disposable_run_step kast-help success kast --help || return 1
  _kast_disposable_run_step kast-agent-help success kast agent --help || return 1
  _kast_disposable_run_step kast-agent-tools success kast agent tools || return 1
}

_kast_disposable_setup_workspace() {
  local workspace
  workspace="$1"
  _kast_disposable_run_step agent-setup success \
    kast --output json agent setup --workspace-root "$workspace" || return 1
  _kast_disposable_run_step agent-setup-instructions success \
    kast --output json agent setup instructions \
    --target-dir "${workspace}/.agents/instructions" \
    --force || return 1
}

_kast_disposable_verify_workspace() {
  local workspace
  workspace="$1"
  _kast_disposable_run_step package-verify success \
    kast --output json agent workflow package-verify \
    --workspace-root "$workspace" \
    --require-skill \
    --skill-target-dir "${workspace}/.agents/skills" \
    --require-instructions \
    --instructions-target-dir "${workspace}/.agents/instructions" \
    --out-dir "${KAST_DISPOSABLE_ARTIFACTS}/package-verify-workflow"
}

_kast_disposable_main() {
  local repo_root root install_idea setup_workspace setup_workspace_path verify run_ready_fix keep shell_mode command_mode timestamp
  repo_root="$(_kast_disposable_repo_root)" || return 1
  root=""
  install_idea=false
  setup_workspace=false
  setup_workspace_path=""
  verify=false
  run_ready_fix=true
  keep=false
  shell_mode=false
  command_mode=false

  while [ "$#" -gt 0 ]; do
    case "$1" in
      --root)
        shift
        [ "$#" -gt 0 ] || { printf 'error: --root requires a directory\n' >&2; return 1; }
        root="$1"
        ;;
      --idea-plugin)
        install_idea=true
        ;;
      --setup-workspace)
        setup_workspace=true
        if [ "${2:-}" ] && [ "${2#-}" = "$2" ]; then
          shift
          setup_workspace_path="$1"
        fi
        ;;
      --verify)
        verify=true
        ;;
      --no-ready-fix)
        run_ready_fix=false
        ;;
      --keep)
        keep=true
        ;;
      --shell)
        shell_mode=true
        ;;
      -h|--help)
        _kast_disposable_usage
        return 0
        ;;
      --)
        shift
        command_mode=true
        break
        ;;
      *)
        printf 'error: unknown argument: %s\n' "$1" >&2
        _kast_disposable_usage >&2
        return 1
        ;;
    esac
    shift
  done

  if _kast_disposable_is_sourced && { [ "$command_mode" = true ] || [ "$shell_mode" = true ]; }; then
    printf 'error: command and shell modes must execute the script instead of sourcing it\n' >&2
    return 1
  fi

  if ! _kast_disposable_is_sourced && [ "$command_mode" = false ] && [ "$shell_mode" = false ]; then
    _kast_disposable_usage >&2
    printf '\nerror: source the script, pass --shell, or pass -- <command>\n' >&2
    return 1
  fi

  if [ -z "$root" ]; then
    timestamp="$(_kast_disposable_timestamp)"
    root="${TMPDIR:-/tmp}/kast-disposable-env.${timestamp}.$$"
  fi
  root="$(_kast_disposable_abs_dir "$root")" || return 1

  _kast_disposable_activate_env "$root" || return 1
  _kast_disposable_install_cli "$repo_root" || return 1
  if [ "$run_ready_fix" = true ]; then
    _kast_disposable_ready_fix || return 1
  fi
  if [ "$install_idea" = true ]; then
    _kast_disposable_install_idea_plugin "$repo_root" || return 1
  fi
  if [ "$setup_workspace" = true ]; then
    if [ -z "$setup_workspace_path" ]; then
      setup_workspace_path="$(pwd -P)"
    fi
    _kast_disposable_setup_workspace "$setup_workspace_path" || return 1
  fi
  if [ "$verify" = true ]; then
    _kast_disposable_verify_surface || return 1
    if [ "$setup_workspace" = true ]; then
      _kast_disposable_verify_workspace "$setup_workspace_path" || return 1
    fi
  fi

  printf 'Kast disposable environment active.\n'
  printf 'Root: %s\n' "$KAST_DISPOSABLE_ROOT"
  printf 'Kast: %s\n' "$(command -v kast)"
  printf 'Artifacts: %s\n' "$KAST_DISPOSABLE_ARTIFACTS"

  if [ "$command_mode" = true ]; then
    "$@"
    local command_status=$?
    if [ "$keep" != true ]; then
      rm -rf -- "$KAST_DISPOSABLE_ROOT"
    else
      printf 'Kept disposable root: %s\n' "$KAST_DISPOSABLE_ROOT"
    fi
    return "$command_status"
  fi

  if [ "$shell_mode" = true ]; then
    "${SHELL:-/bin/bash}"
    local shell_status=$?
    if [ "$keep" != true ]; then
      rm -rf -- "$KAST_DISPOSABLE_ROOT"
    else
      printf 'Kept disposable root: %s\n' "$KAST_DISPOSABLE_ROOT"
    fi
    return "$shell_status"
  fi

  printf 'Run kast_disposable_deactivate to restore the previous environment.\n'
}

_kast_disposable_main "$@"
_kast_disposable_status=$?
if _kast_disposable_is_sourced; then
  return "$_kast_disposable_status"
else
  exit "$_kast_disposable_status"
fi
