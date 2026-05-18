#!/usr/bin/env bash
# run-one.sh: invoke the GitHub Copilot CLI noninteractively for a single
# evaluation run, writing the transcript to the path the dispatcher expects.
#
# Called by evaluation/scripts/dispatch_runs.py via --command-template. The
# dispatcher shell-quotes every placeholder, so no extra quoting is needed
# in the template that invokes this script.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

log()  { printf '%s %s\n' "$1" "$2" >&2; }
die()  { log "error:" "$*"; exit 1; }

instructions=""
transcript=""
run_dir=""
eval_id=""
configuration=""
run_number=""
attempt=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --instructions)  instructions="$2"; shift 2 ;;
    --transcript)    transcript="$2";   shift 2 ;;
    --run-dir)       run_dir="$2";      shift 2 ;;
    --eval-id)       eval_id="$2";      shift 2 ;;
    --configuration) configuration="$2"; shift 2 ;;
    --run-number)    run_number="$2";   shift 2 ;;
    --attempt)       attempt="$2";      shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$instructions" ]] || die "--instructions is required"
[[ -n "$transcript"   ]] || die "--transcript is required"
[[ -n "$run_dir"      ]] || die "--run-dir is required"
[[ -f "$instructions" ]] || die "instructions file not found: $instructions"

prompt_text="$(python3 - "$instructions" <<'PY'
import re
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text()
match = re.search(r"```text\s*\n(?P<prompt>.*?)\n```", text, re.DOTALL)
print((match.group("prompt") if match else text).strip())
PY
)"
[[ -n "$prompt_text" ]] || die "instructions file did not contain a prompt: $instructions"

: "${COPILOT_MODEL:=gpt-5-mini}"
: "${COPILOT_BIN:=copilot}"
: "${COPILOT_OUTPUT_FORMAT:=json}"
: "${COPILOT_EXPERIMENTAL:=1}"
: "${COPILOT_EXTRA_ARGS:=}"
: "${KAST_WORKSPACE_ROOT:=}"

command -v "$COPILOT_BIN" >/dev/null 2>&1 \
  || die "copilot CLI not found on PATH (set COPILOT_BIN to override)"

# Per-run state isolation: pin Copilot's data, state, and cache dirs
# inside the run directory so concurrent workers don't race on shared
# session/log/cache files. Auth config is intentionally left untouched
# so all workers reuse the user's existing Copilot login.
export XDG_DATA_HOME="${run_dir}/.copilot-state/data"
export XDG_STATE_HOME="${run_dir}/.copilot-state/state"
export XDG_CACHE_HOME="${run_dir}/.copilot-state/cache"
mkdir -p \
  "$XDG_DATA_HOME" "$XDG_STATE_HOME" "$XDG_CACHE_HOME" \
  "$(dirname "$transcript")"

stderr_log="${run_dir}/outputs/copilot.stderr.log"

workspace_args=()
if [[ -n "$KAST_WORKSPACE_ROOT" ]]; then
  workspace_args=(-C "$KAST_WORKSPACE_ROOT" --add-dir "$KAST_WORKSPACE_ROOT")
fi

experimental_args=()
if [[ "$COPILOT_EXPERIMENTAL" != "0" && "$COPILOT_EXPERIMENTAL" != "false" ]]; then
  experimental_args=(--experimental)
fi

# shellcheck disable=SC2086  # COPILOT_EXTRA_ARGS is intentionally word-split
"$COPILOT_BIN" \
  "${workspace_args[@]}" \
  --prompt "$prompt_text" \
  --model "$COPILOT_MODEL" \
  --no-color \
  --silent \
  --stream off \
  --output-format "$COPILOT_OUTPUT_FORMAT" \
  --allow-all \
  "${experimental_args[@]}" \
  ${COPILOT_EXTRA_ARGS} \
  >"$transcript" \
  2>"$stderr_log"

[[ -s "$transcript" ]] || die "copilot produced an empty transcript (eval=${eval_id} config=${configuration} run=${run_number} attempt=${attempt}); see ${stderr_log}"
