#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/workspace-sync-status.sh [--manifest PATH] [--strict]

Report the sibling repositories that move with Kast according to
workspace.repos.toml. By default this is informational and exits 0. With
--strict, missing repositories, non-Git paths, remote mismatches, and branch
mismatches fail the command.
USAGE
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/.." && pwd
}

short_head() {
  local repo_path="$1"

  git -C "$repo_path" rev-parse --short HEAD 2>/dev/null || printf '%s' "no-commits"
}

current_branch() {
  local repo_path="$1"

  git -C "$repo_path" symbolic-ref --quiet --short HEAD 2>/dev/null || printf '%s' "detached"
}

manifest_path=""
strict="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest)
      [[ $# -ge 2 ]] || die "Missing value for --manifest"
      manifest_path="$2"
      shift 2
      ;;
    --manifest=*)
      manifest_path="${1#--manifest=}"
      shift
      ;;
    --strict)
      strict="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage
      die "Unknown argument: $1"
      ;;
  esac
done

if [[ -z "$manifest_path" ]]; then
  manifest_path="$(resolve_repo_root)/workspace.repos.toml"
fi

[[ -f "$manifest_path" ]] || die "Workspace manifest not found: ${manifest_path}"
command -v python3 >/dev/null 2>&1 || die "python3 is required to parse ${manifest_path}"

manifest_dir="$(cd -- "$(dirname -- "$manifest_path")" && pwd)"
manifest_file="${manifest_dir}/$(basename -- "$manifest_path")"

printf '%s\n' "Workspace repos"
printf 'manifest: %s\n\n' "$manifest_file"
printf '%-16s %-12s %-16s %-12s %-38s %s\n' \
  "repo" "status" "branch" "commit" "expected remote" "path"

exit_code=0

while IFS=$'\t' read -r repo_name declared_path absolute_path expected_remote expected_branch role; do
  status="missing"
  branch="-"
  commit="-"
  notes=()

  if [[ -e "$absolute_path" ]]; then
    if git -C "$absolute_path" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      status="present"
      branch="$(current_branch "$absolute_path")"
      commit="$(short_head "$absolute_path")"

      actual_remote="$(git -C "$absolute_path" remote get-url origin 2>/dev/null || true)"
      if [[ "$actual_remote" != "$expected_remote" ]]; then
        notes+=("remote:${actual_remote:-missing}")
        exit_code=1
      fi

      if [[ "$branch" != "$expected_branch" ]]; then
        notes+=("expected-branch:${expected_branch}")
        exit_code=1
      fi
    else
      status="not-git"
      exit_code=1
    fi
  else
    exit_code=1
  fi

  if [[ ${#notes[@]} -gt 0 ]]; then
    note_text=" (${notes[*]})"
  else
    note_text=""
  fi

  printf '%-16s %-12s %-16s %-12s %-38s %s%s\n' \
    "$repo_name" "$status" "$branch" "$commit" "$expected_remote" "$declared_path" "$note_text"
  printf '  role: %s\n' "$role"
done < <(
  python3 - "$manifest_file" <<'PY'
import pathlib
import sys

try:
    import tomllib
except ModuleNotFoundError:
    print("python3.11+ with tomllib is required to parse workspace.repos.toml", file=sys.stderr)
    raise SystemExit(1)

manifest = pathlib.Path(sys.argv[1])
data = tomllib.loads(manifest.read_text())
repos = data.get("repos")
if not isinstance(repos, dict):
    print("workspace.repos.toml must contain a [repos] table", file=sys.stderr)
    raise SystemExit(1)

def field(repo_name, config, key):
    value = config.get(key)
    if not isinstance(value, str) or not value.strip():
        print(f"repos.{repo_name}.{key} must be a non-empty string", file=sys.stderr)
        raise SystemExit(1)
    return value.strip()

def clean(value):
    return value.replace("\t", " ").replace("\n", " ")

for repo_name, config in repos.items():
    if not isinstance(config, dict):
        print(f"repos.{repo_name} must be a table", file=sys.stderr)
        raise SystemExit(1)

    declared_path = field(repo_name, config, "path")
    expected_remote = field(repo_name, config, "remote")
    expected_branch = field(repo_name, config, "branch")
    role = field(repo_name, config, "role")

    path = pathlib.Path(declared_path)
    absolute_path = path if path.is_absolute() else manifest.parent / path

    print(
        "\t".join(
            clean(part)
            for part in (
                repo_name,
                declared_path,
                str(absolute_path.resolve(strict=False)),
                expected_remote,
                expected_branch,
                role,
            )
        )
    )
PY
)

if [[ "$strict" == "true" ]]; then
  exit "$exit_code"
fi
