#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'Kast Git quality gate: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-}"
shift || true
[[ "$mode" == "pre-commit" || "$mode" == "pre-push" ]] \
  || die "expected pre-commit or pre-push"

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-quality-gate.XXXXXX")"
cleanup() {
  find "$scratch" -depth -delete
}
trap cleanup EXIT

formatter_path() {
  local configured
  configured="${KAST_IDEA_FORMATTER:-$(git -C "$repo_root" config --get kast.ideaFormatter || true)}"
  if [[ -n "$configured" ]]; then
    [[ -x "$configured" ]] || die "IDEA formatter is not executable: $configured. Configure it with: git config kast.ideaFormatter /path/to/format.sh"
    printf '%s\n' "$configured"
    return
  fi
  for configured in \
    "$HOME/Applications/IntelliJ IDEA.app/Contents/bin/format.sh" \
    "/Applications/IntelliJ IDEA.app/Contents/bin/format.sh"; do
    if [[ -x "$configured" ]]; then
      printf '%s\n' "$configured"
      return
    fi
  done
  die "IDEA formatter was not found. Configure it with: git config kast.ideaFormatter /path/to/format.sh"
}

prepare_snapshot() {
  local snapshot="$1"
  mkdir -p "$snapshot"
  git -C "$snapshot" init --quiet
  git -C "$snapshot" add -f --all
}

run_shape_checks() {
  local snapshot="$1"
  [[ -f "$snapshot/.github/scripts/check-repository-shape.py" ]] \
    || die "snapshot is missing check-repository-shape.py"
  [[ -f "$snapshot/.github/scripts/test-repository-shape-contract.sh" ]] \
    || die "snapshot is missing the repository-shape contract"
  (
    cd "$snapshot"
    bash .github/scripts/test-repository-shape-contract.sh
    python3 .github/scripts/check-repository-shape.py --root "$snapshot"
  )
}

run_formatter() {
  local snapshot="$1"
  shift
  local relative absolute formatter formatter_output common_git_dir idea_state properties
  local -a paths=() repair_paths=()
  for relative in "$@"; do
    case "$relative" in
      *.java|*.kt|*.kts)
        absolute="$snapshot/$relative"
        if [[ -f "$absolute" ]]; then
          paths+=("$absolute")
          repair_paths+=("$repo_root/$relative")
        fi
        ;;
    esac
  done
  formatter="$(formatter_path)"
  ((${#paths[@]} > 0)) || return
  common_git_dir="$(git -C "$repo_root" rev-parse --git-common-dir)"
  [[ "$common_git_dir" = /* ]] || common_git_dir="$repo_root/$common_git_dir"
  idea_state="$common_git_dir/kast-quality-gate"
  mkdir -p "$idea_state"/{config,system,plugins,log}
  properties="$idea_state/idea.properties"
  {
    printf 'idea.config.path=%s\n' "$idea_state/config"
    printf 'idea.system.path=%s\n' "$idea_state/system"
    printf 'idea.plugins.path=%s\n' "$idea_state/plugins"
    printf 'idea.log.path=%s\n' "$idea_state/log"
  } >"$properties"

  formatter_output="$scratch/formatter-output"
  if IDEA_PROPERTIES="$properties" "$formatter" -dry -allowDefaults \
    "${paths[@]}" >"$formatter_output" 2>&1 \
    && ! grep -Fq 'No style for' "$formatter_output"; then
    cat "$formatter_output"
    return
  fi
  cat "$formatter_output" >&2
  if grep -Fq 'No style for' "$formatter_output"; then
    printf 'Kast Git quality gate: IntelliJ IDEA skipped a file without code style settings.\n' >&2
  else
    printf 'Kast Git quality gate: IntelliJ IDEA formatting differs.\n' >&2
  fi
  printf 'Restore the blocked snapshot to the working tree.\nRun the IntelliJ IDEA formatter without -dry:\n  %q -allowDefaults' "$formatter" >&2
  printf ' %q' "${repair_paths[@]}" >&2
  printf '\n' >&2
  return 1
}

check_index() {
  local snapshot="$scratch/index"
  mkdir -p "$snapshot"
  git -C "$repo_root" checkout-index --all --prefix="$snapshot/"
  prepare_snapshot "$snapshot"
  local changed_file="$scratch/index-paths"
  git -C "$repo_root" diff --cached --name-only --diff-filter=ACMR -z >"$changed_file"
  local -a changed=()
  while IFS= read -r -d '' path; do
    changed+=("$path")
  done <"$changed_file"
  run_shape_checks "$snapshot"
  run_formatter "$snapshot" "${changed[@]}"
}

check_commit() {
  local commit="$1"
  local ordinal="$2"
  local snapshot="$scratch/push-$ordinal"
  mkdir -p "$snapshot"
  git -C "$repo_root" archive "$commit" | tar -x -C "$snapshot"
  prepare_snapshot "$snapshot"
  local changed_file="$scratch/push-$ordinal-paths"
  git -C "$repo_root" diff-tree --root --no-commit-id --name-only \
    --diff-filter=ACMR -r -z "$commit" >"$changed_file"
  local -a changed=()
  while IFS= read -r -d '' path; do
    changed+=("$path")
  done <"$changed_file"
  run_shape_checks "$snapshot"
  run_formatter "$snapshot" "${changed[@]}"
}

check_push() {
  local remote_name="${1:-}"
  local remote_location="${2:-}"
  : "$remote_location"
  local local_ref local_oid remote_ref remote_oid commit
  local ordinal=0
  while read -r local_ref local_oid remote_ref remote_oid; do
    : "$local_ref" "$remote_ref"
    [[ -n "${local_oid:-}" && ! "$local_oid" =~ ^0+$ ]] || continue
    local_oid="$(git -C "$repo_root" rev-parse "$local_oid^{commit}" 2>/dev/null)" || continue
    if [[ -n "${remote_oid:-}" && ! "$remote_oid" =~ ^0+$ ]]; then
      commits="$(git -C "$repo_root" rev-list --reverse "$remote_oid..$local_oid")"
    else
      commits="$(git -C "$repo_root" rev-list --reverse "$local_oid" --not --remotes="$remote_name")"
    fi
    while IFS= read -r commit; do
      [[ -n "$commit" ]] || continue
      ordinal=$((ordinal + 1))
      check_commit "$commit" "$ordinal"
    done <<<"$commits"
  done
}

if [[ "$mode" == "pre-commit" ]]; then
  check_index
else
  check_push "$@"
fi
