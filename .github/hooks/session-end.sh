#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/hook-state.sh"

REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"
GRADLE_HOOK_SCRIPT="${REPO_ROOT}/.agents/skills/kotlin-gradle-loop/scripts/gradle/run_gradle_hook.sh"
GRADLE_TASK_SCRIPT="${REPO_ROOT}/.agents/skills/kotlin-gradle-loop/scripts/gradle/run_task.sh"
STATE_FILE="${REPO_ROOT}/.agent-workflow/state.json"
PATH_STATE_FILE="$(hook_state_file "${REPO_ROOT}")"

HOOK_INPUT="$(cat || true)"
export HOOK_INPUT

SESSION_REASON="$(
    python3 - <<'PY'
import json
import os

raw = os.environ.get("HOOK_INPUT", "").strip()
if not raw:
    print("")
else:
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        print("")
    else:
        print(payload.get("reason", ""))
PY
)"

if [[ "${SESSION_REASON}" != "complete" ]]; then
    exit 0
fi

# ── Session export ────────────────────────────────────────────────────────────
# When KAST_SESSION_EXPORT=true the hook converts the raw Copilot session events
# into a markdown transcript and a minimal HTML wrapper and writes them to
# KAST_SESSION_EXPORT_PATH (default: $HOME/.kast/sessions).
if [[ "${KAST_SESSION_EXPORT:-false}" == "true" ]]; then
    SESSION_ID="$(
        python3 - <<'PY'
import json, os
raw = os.environ.get("HOOK_INPUT", "").strip()
if raw:
    try:
        print(json.loads(raw).get("sessionId", ""))
    except json.JSONDecodeError:
        pass
PY
    )"

    if [[ -n "${SESSION_ID}" ]]; then
        EVENTS_FILE="${HOME}/.copilot/session-state/${SESSION_ID}/events.jsonl"
        EXPORT_DIR="${KAST_SESSION_EXPORT_PATH:-${HOME}/.kast/sessions}"

        if [[ -f "${EVENTS_FILE}" ]]; then
            python3 - "${SESSION_ID}" "${EVENTS_FILE}" "${EXPORT_DIR}" <<'PY' || echo "session-end: session export failed (non-fatal)" >&2
import json, sys
from pathlib import Path

session_id = sys.argv[1]
events_file = sys.argv[2]
export_dir = sys.argv[3]

events = []
with open(events_file) as fh:
    for line in fh:
        line = line.strip()
        if line:
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                pass

Path(export_dir).mkdir(parents=True, exist_ok=True)

# ── Markdown ─────────────────────────────────────────────────────────────────
md = [f"# Copilot Session {session_id}", ""]
pending_tools: dict = {}  # toolCallId -> execution_start data

for ev in events:
    etype = ev.get("type", "")
    data = ev.get("data", {})

    if etype == "user.message":
        md.append("### 👤 User")
        md.append("")
        md.append(data.get("content", ""))
        md.append("")
    elif etype == "assistant.message":
        md.append("### 🤖 Assistant")
        md.append("")
        md.append(data.get("content", ""))
        md.append("")
    elif etype == "tool.execution_start":
        pending_tools[data.get("toolCallId")] = data
    elif etype == "tool.execution_complete":
        start = pending_tools.pop(data.get("toolCallId"), {})
        tool_name = start.get("toolName", "unknown")
        status = "✅" if data.get("success", False) else "❌"
        md.append(f"### {status} `{tool_name}`")
        md.append("")
        args = start.get("arguments", {})
        if tool_name == "skill" and "skill" in args:
            md.append(f"**{args['skill']}**")
            md.append("")

(Path(export_dir) / f"copilot-session-{session_id}.md").write_text("\n".join(md))

# ── HTML ──────────────────────────────────────────────────────────────────────
html = [
    "<!DOCTYPE html>",
    "<html>",
    f'<head><meta charset="UTF-8"><title>Copilot Session {session_id}</title></head>',
    "<body>",
    f"<h1>Copilot Session {session_id}</h1>",
]

pending_tools = {}
turn_counter = 0
current_interaction: str | None = None

for ev in events:
    etype = ev.get("type", "")
    data = ev.get("data", {})

    if etype == "tool.execution_start":
        pending_tools[data.get("toolCallId")] = data
    elif etype == "tool.execution_complete":
        start = pending_tools.pop(data.get("toolCallId"), {})
        tool_name = start.get("toolName", "unknown")
        success = data.get("success", False)
        status = "✅" if success else "❌"
        args = start.get("arguments", {})
        turn_counter += 1
        if tool_name == "skill" and "skill" in args:
            skill_arg = args["skill"]
            html.append(f'<div class="turn" id="{turn_counter}">')
            html.append(f"<h2>#{turn_counter} skill - {skill_arg}</h2>")
            html.append(f"<pre>**{skill_arg}**</pre>")
            html.append("</div>")
        else:
            html.append(f'<div class="turn" id="{turn_counter}">')
            html.append(f"<h2>#{turn_counter} {tool_name}</h2>")
            html.append("</div>")

html.extend(["</body>", "</html>"])
(Path(export_dir) / f"copilot-session-{session_id}.html").write_text("\n".join(html))
PY
        fi
    fi
fi
# ── End session export ────────────────────────────────────────────────────────

if [[ ! -f "${PATH_STATE_FILE}" ]]; then
    exit 0
fi

mapfile -t CHANGED_FILES < "${PATH_STATE_FILE}"
rm -f "${PATH_STATE_FILE}"

if [[ "${#CHANGED_FILES[@]}" -eq 0 ]]; then
    exit 0
fi

KOTLIN_FILES=()
NEEDS_GRADLE_VALIDATION="false"

for relative_path in "${CHANGED_FILES[@]}"; do
    case "${relative_path}" in
        *.kt)
            KOTLIN_FILES+=("${REPO_ROOT}/${relative_path}")
            NEEDS_GRADLE_VALIDATION="true"
            ;;
        *.kts|build.gradle|build.gradle.kts|settings.gradle|settings.gradle.kts|gradle.properties|gradlew|gradlew.bat|gradle/*|scripts/gradle/*)
            NEEDS_GRADLE_VALIDATION="true"
            ;;
    esac
done

require_ok_json() {
    local label="$1"
    local payload="$2"
    python3 - "${label}" <<'PY' <<<"${payload}"
import json
import sys

label = sys.argv[1]
payload = json.load(sys.stdin)
if payload.get("ok"):
    sys.exit(0)

message = payload.get("error") or payload.get("message") or payload.get("failure_summary") or "unknown failure"
print(f"{label} failed: {message}", file=sys.stderr)
sys.exit(1)
PY
}

if [[ "${#KOTLIN_FILES[@]}" -gt 0 ]]; then
    if ! KAST_BIN="$(bash "${SCRIPT_DIR}/resolve-kast-cli-path.sh")"; then
        echo "session-end: unable to resolve KAST_CLI_PATH; cannot run kast skill diagnostics" >&2
        exit 1
    fi
    DIAGNOSTICS_REQUEST="$(
        python3 - "${REPO_ROOT}" "${KOTLIN_FILES[@]}" <<'PY'
import json
import sys

workspace_root = sys.argv[1]
file_paths = sys.argv[2:]
print(json.dumps({"workspaceRoot": workspace_root, "filePaths": file_paths}))
PY
    )"
    DIAGNOSTICS_OUTPUT="$("${KAST_BIN}" skill diagnostics "${DIAGNOSTICS_REQUEST}")"
    python3 - <<'PY' <<<"${DIAGNOSTICS_OUTPUT}"
import json
import sys

payload = json.load(sys.stdin)
if payload.get("ok") and payload.get("clean"):
    sys.exit(0)

message = payload.get("message") or f"Diagnostics reported {payload.get('error_count', 'unknown')} errors"
print(f"kast skill diagnostics failed: {message}", file=sys.stderr)
sys.exit(1)
PY
fi

if [[ "${NEEDS_GRADLE_VALIDATION}" != "true" ]]; then
    exit 0
fi

if [[ -f "${STATE_FILE}" ]]; then
    BUILD_HEALTH_OUTPUT="$(bash "${GRADLE_HOOK_SCRIPT}" "${REPO_ROOT}")"
else
    BUILD_HEALTH_OUTPUT="$(bash "${GRADLE_TASK_SCRIPT}" "${REPO_ROOT}" check)"
fi
require_ok_json "build-health" "${BUILD_HEALTH_OUTPUT}"

TEST_OUTPUT="$(bash "${GRADLE_TASK_SCRIPT}" "${REPO_ROOT}" test)"
require_ok_json "run-tests" "${TEST_OUTPUT}"
