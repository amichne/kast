#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s\n' "$*" >&2
}

die() {
  log "error: $*"
  exit 1
}

resolve_script_dir() {
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd
}

can_prompt() {
  [[ -r /dev/tty && -w /dev/tty ]]
}

prompt_yes_no() {
  local message="$1"
  local default_answer="${2:-yes}"
  local prompt_suffix="[Y/n]"
  local reply=""

  if [[ "$default_answer" == "no" ]]; then
    prompt_suffix="[y/N]"
  fi

  while true; do
    printf '? %s %s ' "$message" "$prompt_suffix" >/dev/tty
    if ! IFS= read -r reply </dev/tty; then
      printf '\n' >/dev/tty
      return 1
    fi
    printf '\n' >/dev/tty

    case "$reply" in
      "")
        [[ "$default_answer" == "yes" ]]
        return
        ;;
      [Yy] | [Yy][Ee][Ss])
        return 0
        ;;
      [Nn] | [Nn][Oo])
        return 1
        ;;
    esac
  done
}

resolve_packaged_skill_path() {
  local script_dir="$1"
  local candidate="${KAST_SKILL_PATH:-${script_dir}/../share/skills/kast}"

  [[ -d "$candidate" ]] || die "Packaged kast skill was not found: $candidate"
  cd -- "$candidate" >/dev/null 2>&1 && pwd
}

resolve_default_target_dir() {
  local cwd="$1"

  if [[ -d "${cwd}/.agents/skill" ]]; then
    printf '%s\n' "${cwd}/.agents/skill"
    return
  fi

  if [[ -d "${cwd}/.agents/skills" || -d "${cwd}/.agents" ]]; then
    printf '%s\n' "${cwd}/.agents/skills"
    return
  fi

  if [[ -d "${cwd}/.github/skills" || -d "${cwd}/.github" ]]; then
    printf '%s\n' "${cwd}/.github/skills"
    return
  fi

  if [[ -d "${cwd}/.claude/skills" || -d "${cwd}/.claude" ]]; then
    printf '%s\n' "${cwd}/.claude/skills"
    return
  fi

  printf '%s\n' "${cwd}/.agents/skills"
}

usage() {
  cat <<'USAGE' >&2
Usage: kast-skilled [--target-dir <path>] [--link-name <name>] [--yes]

Creates a symlink to the packaged kast skill in the current workspace.

Defaults:
  target-dir: .agents/skills, .github/skills, or .claude/skills based on what
              already exists in the current directory; falls back to
              .agents/skills when none exist
  link-name:  kast

Environment:
  KAST_SKILL_PATH  Override the packaged skill root to symlink from.
USAGE
}

script_dir="$(resolve_script_dir)"
packaged_skill_path="$(resolve_packaged_skill_path "$script_dir")"
target_dir=""
link_name="kast"
assume_yes="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-dir)
      [[ $# -ge 2 ]] || die "Missing value for --target-dir"
      target_dir="$2"
      shift 2
      ;;
    --link-name)
      [[ $# -ge 2 ]] || die "Missing value for --link-name"
      link_name="$2"
      shift 2
      ;;
    --yes)
      assume_yes="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

[[ "$link_name" =~ ^[A-Za-z0-9._-]+$ ]] || die "Link name may contain only letters, digits, dot, underscore, and dash"

if [[ -z "$target_dir" ]]; then
  target_dir="$(resolve_default_target_dir "$PWD")"
fi

mkdir -p "$target_dir"
target_dir="$(cd -- "$target_dir" >/dev/null 2>&1 && pwd)"
target_path="${target_dir}/${link_name}"

if [[ -L "$target_path" ]]; then
  if [[ "$(readlink "$target_path")" == "$packaged_skill_path" ]]; then
    log "Packaged kast skill is already linked at ${target_path}"
    exit 0
  fi

  if [[ "$assume_yes" != "true" ]]; then
    can_prompt || die "Target symlink already exists at ${target_path}; rerun with --yes to replace it"
    prompt_yes_no "Replace existing symlink at ${target_path}?" "yes" || {
      log "Skipped replacing ${target_path}"
      exit 0
    }
  fi

  rm -f "$target_path"
elif [[ -e "$target_path" ]]; then
  die "Target already exists and is not a symlink: ${target_path}"
fi

if [[ "$assume_yes" != "true" ]]; then
  can_prompt || die "No interactive terminal is available; rerun with --yes to confirm the install path"
  prompt_yes_no "Link packaged kast skill from ${packaged_skill_path} into ${target_path}?" "yes" || {
    log "Skipped installing packaged kast skill"
    exit 0
  }
fi

ln -s "$packaged_skill_path" "$target_path"
log "Linked packaged kast skill: ${target_path} -> ${packaged_skill_path}"
