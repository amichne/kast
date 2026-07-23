#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  printf 'error: this smoke gate requires macOS\n' >&2
  exit 1
fi

workspace="${1:-$PWD}"
kast_binary="${KAST_SMOKE_KAST:-${KAST_HOME:-$HOME/.local/share/kast}/current/bin/kast}"
scenario="${KAST_SMOKE_SCENARIO:-unspecified}"
expected_disposition="${KAST_SMOKE_EXPECT_DISPOSITION:-}"
timeout_seconds="${KAST_SMOKE_READY_TIMEOUT_SECONDS:-300}"

workspace="$(cd -- "$workspace" && pwd -P)"
[[ -x "$kast_binary" ]] || {
  printf 'error: Kast CLI is not executable: %s\n' "$kast_binary" >&2
  exit 1
}

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-macos-idea-smoke.XXXXXX")"
cleanup() {
  find "$scratch" -depth -delete
}
trap cleanup EXIT

front_before="$(lsappinfo front 2>/dev/null || true)"
"$kast_binary" --output json developer runtime up \
  --workspace-root "$workspace" \
  --backend idea \
  --accept-indexing >"$scratch/up.json"
front_after="$(lsappinfo front 2>/dev/null || true)"

if [[ -n "$front_before" && "$front_before" != "$front_after" ]]; then
  printf 'error: frontmost application changed during project open\n' >&2
  exit 1
fi

python3 - "$scratch/up.json" "$workspace" "$expected_disposition" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text())
root = sys.argv[2]
expected_disposition = sys.argv[3]
if payload.get("workspaceRoot") != root:
    raise SystemExit("workspace-up returned a different root")
selected = payload.get("selected") or {}
descriptor = selected.get("descriptor") or {}
if descriptor.get("workspaceRoot") != root:
    raise SystemExit("selected descriptor returned a different root")
state = (selected.get("runtimeStatus") or {}).get("state")
if state not in {"INDEXING", "READY"}:
    raise SystemExit(f"initial runtime state is not INDEXING or READY: {state}")
if expected_disposition and payload.get("launchDisposition") != expected_disposition:
    raise SystemExit(
        "launch disposition mismatch: "
        f"{payload.get('launchDisposition')} != {expected_disposition}"
    )
pid = descriptor.get("pid")
if not isinstance(pid, int) or pid <= 0:
    raise SystemExit("selected descriptor has no valid IDE process ID")
print(pid)
PY

deadline=$((SECONDS + timeout_seconds))
while ((SECONDS < deadline)); do
  if "$kast_binary" --output json status \
    --workspace-root "$workspace" \
    --backend idea >"$scratch/status.json" &&
    python3 - "$scratch/status.json" "$workspace" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text())
selected = payload.get("selected") or {}
descriptor = selected.get("descriptor") or {}
status = selected.get("runtimeStatus") or {}
if descriptor.get("workspaceRoot") != sys.argv[2]:
    raise SystemExit(1)
if status.get("state") != "READY" or not status.get("referenceIndexReady"):
    raise SystemExit(1)
PY
  then
    printf 'PASS %s: exact root reached READY without changing focus\n' "$scenario"
    printf 'Observe that only actionable Kast failures produced notifications.\n'
    exit 0
  fi
  sleep 2
done

printf 'error: exact root did not reach READY within %ss\n' "$timeout_seconds" >&2
exit 1
