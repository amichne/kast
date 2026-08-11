#!/usr/bin/env bash
set -euo pipefail

# INTENT
# ------
# This command is the handoff boundary between a sibling performance task and
# enterprise-machine testing. Invoke it once for one intentionally small,
# evidence-backed performance increment. The sibling task decides that an
# increment is meaningful only after comparable correctness and performance
# evidence exists; this command does not turn an unmeasured change into proof.
#
# `checkpoint` preserves the exact committed increment and its committed
# evidence on a non-forced `performance/*` branch. `release` repeats that
# checkpoint, then cuts the next stable patch release only when the same commit
# is already remote `main` and its exact-source push CI is green. Publication is
# delegated to Kast's existing `cut-release.yml`; this command never creates a
# partial release, bypasses branch protection, or claims enterprise results.

readonly DESCRIPTION='Preserve one measured performance nibble and publish its exact patch release for enterprise machine testing.'

toon_quote() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  printf '"%s"' "$value"
}

emit_error() {
  local code="$1"
  local message="$2"
  local help_text="$3"
  printf '%s\n' 'error:'
  printf '  code: %s\n' "$(toon_quote "$code")"
  printf '  message: %s\n' "$(toon_quote "$message")"
  printf '%s\n' 'help[1]:'
  printf '  %s\n' "$(toon_quote "$help_text")"
}

fail_usage() {
  emit_error "$1" "$2" "$3"
  exit 2
}

fail_runtime() {
  emit_error "$1" "$2" "$3"
  exit 1
}

usage() {
  cat <<'USAGE'
publish-performance-increment

Preserve one intentionally small, evidence-backed performance increment and
publish its exact patch release for enterprise machine testing.

The performance sibling owns the meaningfulness decision: retain comparable
correctness and performance measurements in a committed evidence file first.
This command preserves that proof with the exact commit. It does not benchmark,
merge, or bypass branch protection.

Usage:
  publish-performance-increment checkpoint \
    --branch <branch> --evidence <path> [--remote <remote>]

  publish-performance-increment release \
    --branch <branch> --evidence <path> --repository <owner/name> \
    [--remote <remote>]

Commands:
  checkpoint  Push exact HEAD to a performance/* branch after admitting the
              clean commit and its committed, non-empty evidence file.
  release     Checkpoint, require exact remote main and green exact-source CI,
              dispatch the existing patch workflow, wait, and verify the new
              stable tag and published release.

Flags:
  --branch <branch>             Required performance/* checkpoint branch.
  --evidence <path>             Required repository-relative evidence file.
  --repository <owner/name>     Required by release; GitHub repository.
  --remote <remote>             Git remote to push and inspect (default: origin).
  --help                        Show this complete reference.

Examples:
  scripts/release/publish-performance-increment.sh checkpoint \
    --branch performance/enterprise-nibbles \
    --evidence .agent/performance-audit/increment-01.json

  scripts/release/publish-performance-increment.sh release \
    --branch performance/enterprise-nibbles \
    --evidence .agent/performance-audit/increment-01.json \
    --repository amichne/kast

After checkpoint, promote that exact commit to main through the repository's
approved path. Then run release from the same commit. A successful release call
returns only after the patch workflow and stable release are terminal.
USAGE
}

display_executable() {
  local executable="$1"
  if [[ -n "${HOME:-}" && "$executable" == "$HOME"/* ]]; then
    printf '~/%s' "${executable#"$HOME"/}"
  else
    printf '%s' "$executable"
  fi
}

show_home() {
  local executable
  local repo_root
  local head
  local branch
  local cleanliness
  executable="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/$(basename -- "${BASH_SOURCE[0]}")"

  printf '%s\n' 'tool:'
  printf '  bin: %s\n' "$(toon_quote "$(display_executable "$executable")")"
  printf '  description: %s\n' "$(toon_quote "$DESCRIPTION")"

  if repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" \
      && head="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null)"; then
    branch="$(git -C "$repo_root" branch --show-current 2>/dev/null || true)"
    if [[ -z "$(git -C "$repo_root" status --porcelain --untracked-files=all 2>/dev/null)" ]]; then
      cleanliness='clean'
    else
      cleanliness='dirty'
    fi
    printf '%s\n' 'workspace:'
    printf '  root: %s\n' "$(toon_quote "$repo_root")"
    printf '  branch: %s\n' "$(toon_quote "${branch:-detached}")"
    printf '  head: %s\n' "$(toon_quote "$head")"
    printf '  state: %s\n' "$(toon_quote "$cleanliness")"
  else
    printf '%s\n' 'workspace:'
    printf '  state: %s\n' "$(toon_quote 'unavailable')"
  fi

  printf '%s\n' 'help[2]:'
  printf '  %s\n' "$(toon_quote 'Run `publish-performance-increment checkpoint --help` to preserve a measured nibble.')"
  printf '  %s\n' "$(toon_quote 'Run `publish-performance-increment release --help` after the same commit reaches green remote main.')"
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 \
    || fail_runtime \
      'required_capability_unavailable' \
      "The required $command_name capability is unavailable." \
      'Install the missing capability, then rerun the same command.'
}

validate_relative_evidence_path() {
  local evidence="$1"
  case "$evidence" in
    ''|/*|..|../*|*/..|*/../*)
      fail_usage \
        'invalid_evidence_path' \
        'Evidence must be a repository-relative path without parent traversal.' \
        'Pass `--evidence <path>` for a committed file inside this repository.'
      ;;
  esac
}

parse_arguments() {
  COMMAND="$1"
  shift
  BRANCH=''
  EVIDENCE=''
  REMOTE='origin'
  REPOSITORY=''

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --branch)
        [[ $# -ge 2 ]] || fail_usage \
          'missing_argument_value' '--branch requires a value.' \
          'Pass `--branch performance/<name>`.'
        BRANCH="$2"
        shift 2
        ;;
      --evidence)
        [[ $# -ge 2 ]] || fail_usage \
          'missing_argument_value' '--evidence requires a value.' \
          'Pass `--evidence <path>`.'
        EVIDENCE="$2"
        shift 2
        ;;
      --remote)
        [[ $# -ge 2 ]] || fail_usage \
          'missing_argument_value' '--remote requires a value.' \
          'Pass `--remote <remote>` or omit it for origin.'
        REMOTE="$2"
        shift 2
        ;;
      --repository)
        [[ "$COMMAND" == release ]] || fail_usage \
          'unknown_argument' '--repository is not valid for checkpoint.' \
          'Valid checkpoint flags: --branch, --evidence, --remote, --help.'
        [[ $# -ge 2 ]] || fail_usage \
          'missing_argument_value' '--repository requires a value.' \
          'Pass `--repository <owner/name>`.'
        REPOSITORY="$2"
        shift 2
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        if [[ "$COMMAND" == release ]]; then
          fail_usage \
            'unknown_argument' "Unknown release argument: $1" \
            'Valid release flags: --branch, --evidence, --repository, --remote, --help.'
        else
          fail_usage \
            'unknown_argument' "Unknown checkpoint argument: $1" \
            'Valid checkpoint flags: --branch, --evidence, --remote, --help.'
        fi
        ;;
    esac
  done

  [[ -n "$BRANCH" ]] || fail_usage \
    'branch_required' '--branch is required.' \
    'Pass `--branch performance/<name>`.'
  [[ -n "$EVIDENCE" ]] || fail_usage \
    'evidence_required' '--evidence is required.' \
    'Pass `--evidence <path>` for the committed measurement evidence.'
  if [[ "$COMMAND" == release && -z "$REPOSITORY" ]]; then
    fail_usage \
      'repository_required' '--repository is required for release.' \
      'Pass `--repository <owner/name>`.'
  fi

  [[ "$BRANCH" == performance/* ]] || fail_usage \
    'invalid_checkpoint_branch' 'Checkpoint branches must use the performance/* namespace.' \
    'Pass `--branch performance/<name>`.'
  [[ "$REMOTE" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] || fail_usage \
    'invalid_remote' 'Remote names must be explicit non-option identifiers.' \
    'Pass `--remote <remote>` or omit it for origin.'
  if [[ -n "$REPOSITORY" && ! "$REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    fail_usage \
      'invalid_repository' 'Repository must have owner/name form.' \
      'Pass `--repository <owner/name>`.'
  fi
  validate_relative_evidence_path "$EVIDENCE"
}

resolve_clean_committed_increment() {
  local status

  REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" \
    || fail_runtime \
      'repository_unavailable' 'The current directory is not inside a Git repository.' \
      'Run this command from the Kast repository.'
  cd -- "$REPO_ROOT"

  git check-ref-format --branch "$BRANCH" >/dev/null 2>&1 \
    || fail_usage \
      'invalid_checkpoint_branch' 'The checkpoint branch is not a valid Git branch name.' \
      'Pass `--branch performance/<name>`.'

  status="$(git status --porcelain --untracked-files=all 2>/dev/null)" \
    || fail_runtime \
      'worktree_state_unavailable' 'The worktree state could not be inspected.' \
      'Repair the local worktree, then rerun the same command.'
  [[ -z "$status" ]] || fail_runtime \
    'increment_not_committed' 'The worktree is not clean; the increment and its evidence must be one committed state.' \
    'Commit only the measured increment and evidence, then rerun the same command.'

  [[ -f "$EVIDENCE" && -s "$EVIDENCE" ]] || fail_runtime \
    'evidence_unavailable' 'The evidence path is not a non-empty regular file.' \
    'Write the comparable enterprise-test evidence, commit it, then rerun.'
  git ls-files --error-unmatch -- "$EVIDENCE" >/dev/null 2>&1 \
    && git cat-file -e "HEAD:$EVIDENCE" >/dev/null 2>&1 \
    || fail_runtime \
      'evidence_not_committed' 'The evidence file is not present in exact HEAD.' \
      'Commit the evidence with the performance increment, then rerun.'

  HEAD_SHA="$(git rev-parse HEAD 2>/dev/null)" \
    || fail_runtime \
      'head_unavailable' 'Exact HEAD could not be resolved.' \
      'Repair the local repository, then rerun the same command.'
  [[ "$HEAD_SHA" =~ ^[0-9a-f]{40}$ ]] || fail_runtime \
    'head_unavailable' 'Exact HEAD is not a full commit identity.' \
    'Repair the local repository, then rerun the same command.'
}

push_checkpoint() {
  local remote_record
  local remote_sha

  printf 'checkpoint: pushing exact HEAD to %s\n' "$BRANCH" >&2
  GIT_TERMINAL_PROMPT=0 git push "$REMOTE" "HEAD:refs/heads/$BRANCH" \
    >/dev/null 2>&1 \
    || fail_runtime \
      'checkpoint_push_failed' 'The non-forced checkpoint push was rejected.' \
      'Resolve the remote branch divergence, then rerun without force-pushing.'

  remote_record="$(GIT_TERMINAL_PROMPT=0 git ls-remote --exit-code \
    "$REMOTE" "refs/heads/$BRANCH" 2>/dev/null)" \
    || fail_runtime \
      'checkpoint_identity_unavailable' 'The pushed checkpoint identity could not be read back.' \
      'Inspect remote access, then rerun the same command.'
  remote_sha="$(awk 'NR == 1 { value=$1 } END { if (NR == 1) print value }' \
    <<< "$remote_record")"
  [[ "$remote_sha" == "$HEAD_SHA" ]] || fail_runtime \
    'checkpoint_identity_mismatch' 'The remote checkpoint branch does not resolve to exact HEAD.' \
    'Do not release; inspect the remote branch identity.'
}

emit_checkpoint() {
  printf '%s\n' 'increment:'
  printf '  status: %s\n' "$(toon_quote 'checkpointed')"
  printf '  head: %s\n' "$(toon_quote "$HEAD_SHA")"
  printf '  branch: %s\n' "$(toon_quote "$BRANCH")"
  printf '  evidence: %s\n' "$(toon_quote "$EVIDENCE")"
  printf '  intent: %s\n' "$(toon_quote 'enterprise-machine performance testing')"
  printf '%s\n' 'help[1]:'
  printf '  %s\n' "$(toon_quote 'Promote this exact commit to main through the approved repository path, then run `release` from the same commit.')"
}

latest_stable_tag() {
  local records="$1"
  local record_sha
  local record_ref
  local tag
  local major
  local minor
  local patch
  local best_major=0
  local best_minor=0
  local best_patch=0
  local found=false

  while read -r record_sha record_ref; do
    tag="${record_ref#refs/tags/}"
    if [[ "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
      major=$((10#${BASH_REMATCH[1]}))
      minor=$((10#${BASH_REMATCH[2]}))
      patch=$((10#${BASH_REMATCH[3]}))
      if [[ "$found" == false ]] \
          || (( major > best_major )) \
          || (( major == best_major && minor > best_minor )) \
          || (( major == best_major && minor == best_minor && patch > best_patch )); then
        best_major="$major"
        best_minor="$minor"
        best_patch="$patch"
        found=true
      fi
    fi
  done <<< "$records"

  if [[ "$found" == false ]]; then
    printf '%s\n' 'v0.0.0'
  else
    printf 'v%s.%s.%s\n' "$best_major" "$best_minor" "$best_patch"
  fi
}

next_patch_tag() {
  local current_tag="$1"
  local version="${current_tag#v}"
  local major
  local minor
  local patch
  IFS='.' read -r major minor patch <<< "$version"
  printf 'v%s.%s.%s\n' "$major" "$minor" "$((patch + 1))"
}

require_exact_green_main() {
  local main_record
  local main_sha
  local ci_runs
  local ci_run
  local ci_status
  local ci_conclusion

  main_record="$(GIT_TERMINAL_PROMPT=0 git ls-remote --exit-code \
    "$REMOTE" refs/heads/main 2>/dev/null)" \
    || fail_runtime \
      'main_identity_unavailable' 'Remote main identity could not be resolved.' \
      'Inspect remote access, then rerun the same command.'
  main_sha="$(awk 'NR == 1 { value=$1 } END { if (NR == 1) print value }' \
    <<< "$main_record")"
  [[ "$main_sha" == "$HEAD_SHA" ]] || fail_runtime \
    'main_not_exact_head' 'Remote main does not resolve to the checkpointed exact HEAD.' \
    'Promote the checkpoint through the approved main path, then rerun release from the same commit.'

  ci_runs="$(GH_PROMPT_DISABLED=1 gh run list \
    --repo "$REPOSITORY" \
    --workflow ci.yml \
    --branch main \
    --commit "$HEAD_SHA" \
    --event push \
    --limit 20 \
    --json databaseId,headSha,headBranch,event,status,conclusion,url 2>/dev/null)" \
    || fail_runtime \
      'exact_source_ci_unavailable' 'Exact-source CI state could not be read.' \
      'Restore repository access, then rerun release.'
  ci_run="$(jq -cer --arg head "$HEAD_SHA" '
    [ .[] | select(
        .headSha == $head and
        .headBranch == "main" and
        .event == "push"
      ) ]
    | sort_by(.databaseId)
    | last // empty
  ' <<< "$ci_runs" 2>/dev/null)" \
    || fail_runtime \
      'exact_source_ci_unavailable' 'No exact-source main push CI run exists for the checkpointed commit.' \
      'Wait for the main push CI run to appear, then rerun release.'
  ci_status="$(jq -r '.status' <<< "$ci_run")"
  ci_conclusion="$(jq -r '.conclusion // ""' <<< "$ci_run")"
  [[ "$ci_status" == completed && "$ci_conclusion" == success ]] \
    || fail_runtime \
      'exact_source_ci_not_green' "Exact-source CI is $ci_status/${ci_conclusion:-pending}, not terminal-success." \
      'Wait for or repair exact-source main CI, then rerun release.'
}

publish_patch_release() {
  local tags_before
  local current_tag
  local expected_tag
  local workflow_url
  local run_id
  local tags_after
  local published_sha
  local release_record

  tags_before="$(GIT_TERMINAL_PROMPT=0 git ls-remote --tags --refs \
    "$REMOTE" 'refs/tags/v*' 2>/dev/null)" \
    || fail_runtime \
      'release_tags_unavailable' 'Stable release tags could not be inspected.' \
      'Restore remote access, then rerun release.'
  current_tag="$(latest_stable_tag "$tags_before")"
  expected_tag="$(next_patch_tag "$current_tag")"

  printf 'release: dispatching %s from exact main %s\n' "$expected_tag" "$HEAD_SHA" >&2
  workflow_url="$(GH_PROMPT_DISABLED=1 gh workflow run cut-release.yml \
    --repo "$REPOSITORY" \
    --ref main \
    --raw-field release_type=patch 2>/dev/null)" \
    || fail_runtime \
      'release_dispatch_failed' 'The authoritative patch workflow was not dispatched.' \
      'Inspect release workflow access, then rerun release.'
  if [[ "$workflow_url" =~ /actions/runs/([0-9]+)([/?].*)?$ ]]; then
    run_id="${BASH_REMATCH[1]}"
  else
    fail_runtime \
      'release_run_identity_unavailable' 'The patch workflow did not return an exact run identity.' \
      'Inspect the Cut Release workflow before attempting another dispatch.'
  fi

  printf 'release: waiting for workflow run %s\n' "$run_id" >&2
  GH_PROMPT_DISABLED=1 gh run watch "$run_id" \
    --repo "$REPOSITORY" --exit-status --compact >/dev/null 2>&1 \
    || fail_runtime \
      'release_workflow_failed' 'The patch release workflow did not finish successfully.' \
      "Inspect workflow run $run_id; do not dispatch a replacement until its terminal state is understood."

  tags_after="$(GIT_TERMINAL_PROMPT=0 git ls-remote --tags --refs \
    "$REMOTE" 'refs/tags/v*' 2>/dev/null)" \
    || fail_runtime \
      'released_tag_unavailable' 'Release workflow succeeded but its tag could not be read back.' \
      'Inspect the terminal release run and remote tag state.'
  published_sha="$(awk -v ref="refs/tags/$expected_tag" '$2 == ref { print $1 }' \
    <<< "$tags_after")"
  [[ "$published_sha" == "$HEAD_SHA" ]] || fail_runtime \
    'released_tag_identity_mismatch' 'The expected patch tag does not resolve to exact HEAD.' \
    'Inspect the terminal release run and do not test an unbound release.'

  release_record="$(GH_PROMPT_DISABLED=1 gh release view "$expected_tag" \
    --repo "$REPOSITORY" \
    --json isDraft,isPrerelease,tagName,url 2>/dev/null)" \
    || fail_runtime \
      'published_release_unavailable' 'The expected patch release could not be read back.' \
      'Inspect the terminal release run and published release state.'
  jq -e --arg tag "$expected_tag" '
    .tagName == $tag and
    .isDraft == false and
    .isPrerelease == false and
    (.url | type == "string" and length > 0)
  ' <<< "$release_record" >/dev/null 2>&1 \
    || fail_runtime \
      'published_release_invalid' 'The patch release is not a published stable release.' \
      'Inspect the terminal release run before enterprise-machine testing.'

  printf '%s\n' 'increment:'
  printf '  status: %s\n' "$(toon_quote 'released')"
  printf '  head: %s\n' "$(toon_quote "$HEAD_SHA")"
  printf '  branch: %s\n' "$(toon_quote "$BRANCH")"
  printf '  evidence: %s\n' "$(toon_quote "$EVIDENCE")"
  printf '  tag: %s\n' "$(toon_quote "$expected_tag")"
  printf '  workflowRun: %s\n' "$(toon_quote "$run_id")"
  printf '  releaseUrl: %s\n' "$(toon_quote "$(jq -r '.url' <<< "$release_record")")"
  printf '  intent: %s\n' "$(toon_quote 'install this exact patch on the enterprise machine and retain the resulting measurements')"
}

case "${1:-}" in
  '')
    require_command git
    show_home
    exit 0
    ;;
  --help|-h)
    usage
    exit 0
    ;;
  checkpoint|release)
    parse_arguments "$@"
    ;;
  *)
    fail_usage \
      'unknown_command' "Unknown command: $1" \
      'Valid commands: checkpoint, release. Use --help for the complete reference.'
    ;;
esac

require_command git
if [[ "$COMMAND" == release ]]; then
  require_command gh
  require_command jq
fi

resolve_clean_committed_increment
push_checkpoint

if [[ "$COMMAND" == checkpoint ]]; then
  emit_checkpoint
  exit 0
fi

require_exact_green_main
publish_patch_release
