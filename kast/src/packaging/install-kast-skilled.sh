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
Usage: kast-skilled [--target-dir <path>] [--name <name>] [--yes]

Copies the packaged kast skill into the current workspace.

Defaults:
  target-dir: .agents/skills, .github/skills, or .claude/skills based on what
               already exists in the current directory; falls back to
               .agents/skills when none exist
  name:       kast

Environment:
  KAST_SKILL_PATH  Override the packaged skill root to copy from.
USAGE
}

script_dir="$(resolve_script_dir)"
packaged_skill_path="$(resolve_packaged_skill_path "$script_dir")"
target_dir=""
skill_name="kast"
assume_yes="false"
packaged_skill_version=""

if [[ -f "${packaged_skill_path}/.kast-version" ]]; then
  packaged_skill_version="$(<"${packaged_skill_path}/.kast-version")"
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-dir)
      [[ $# -ge 2 ]] || die "Missing value for --target-dir"
      target_dir="$2"
      shift 2
      ;;
    --name)
      [[ $# -ge 2 ]] || die "Missing value for --name"
      skill_name="$2"
      shift 2
      ;;
    --link-name)
      [[ $# -ge 2 ]] || die "Missing value for --link-name"
      skill_name="$2"
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

[[ "$skill_name" =~ ^[A-Za-z0-9._-]+$ ]] || die "Skill name may contain only letters, digits, dot, underscore, and dash"
[[ "$skill_name" != "." && "$skill_name" != ".." ]] || die "Skill name must not be '.' or '..'"

if [[ -z "$target_dir" ]]; then
  target_dir="$(resolve_default_target_dir "$PWD")"
fi

mkdir -p "$target_dir"
target_dir="$(cd -- "$target_dir" >/dev/null 2>&1 && pwd)"
target_path="${target_dir}/${skill_name}"

if [[ -d "$target_path" && -n "$packaged_skill_version" && -f "${target_path}/.kast-version" ]]; then
  if [[ "$(<"${target_path}/.kast-version")" == "$packaged_skill_version" ]]; then
    log "Packaged kast skill is already installed at ${target_path} (version ${packaged_skill_version})"
    exit 0
  fi
fi

if [[ -e "$target_path" || -L "$target_path" ]]; then
  if [[ "$assume_yes" != "true" ]]; then
    can_prompt || die "Packaged kast skill already exists at ${target_path}; rerun with --yes to overwrite it"
    prompt_yes_no "Replace existing packaged kast skill at ${target_path}?" "yes" || {
      log "Skipped replacing ${target_path}"
      exit 0
    }
  fi

  rm -rf "$target_path"
fi

if [[ "$assume_yes" != "true" ]]; then
  can_prompt || die "No interactive terminal is available; rerun with --yes to confirm the install path"
  prompt_yes_no "Copy packaged kast skill from ${packaged_skill_path} into ${target_path}?" "yes" || {
    log "Skipped installing packaged kast skill"
    exit 0
  }
fi

mkdir -p "$target_path"
cp -R "$packaged_skill_path/." "$target_path/"
if [[ -n "$packaged_skill_version" ]]; then
  printf '%s\n' "$packaged_skill_version" > "${target_path}/.kast-version"
fi
log "Installed packaged kast skill at ${target_path}"
