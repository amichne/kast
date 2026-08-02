#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  printf 'error: this smoke gate requires macOS\n' >&2
  exit 1
fi

workspace="${1:-$PWD}"
kast_root="${KAST_HOME:-$HOME/.local/share/kast}"
kast_binary="${KAST_SMOKE_KAST:-${kast_root}/current/bin/kast}"
kastctl_binary="${KAST_SMOKE_KASTCTL:-${kast_root}/current/libexec/kastctl}"
scenario="${KAST_SMOKE_SCENARIO:-unspecified}"
identity_file="${KAST_SMOKE_IDENTITY_FILE:-${workspace}/build/kast-headless-smoke-identity.json}"

workspace="$(cd -- "$workspace" && pwd -P)"
[[ -x "$kast_binary" ]] || {
  printf 'error: Kast agent CLI is not executable: %s\n' "$kast_binary" >&2
  exit 1
}
[[ -x "$kastctl_binary" ]] || {
  printf 'error: Kast control CLI is not executable: %s\n' "$kastctl_binary" >&2
  exit 1
}

case "$scenario" in
  open|closed) ;;
  *)
    printf 'error: set KAST_SMOKE_SCENARIO to open or closed\n' >&2
    exit 1
    ;;
esac

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-macos-headless-smoke.XXXXXX")"
cleanup() {
  find "$scratch" -depth -delete
}
trap cleanup EXIT

# Runtime demand uses the public agent surface from the canonical workspace.
# The smoke does not inspect, launch, close, focus, or otherwise control a
# foreground application.
(
  cd -- "$workspace"
  "$kast_binary" up >"$scratch/up.toon"
)

grep -Eq '^ready: true$' "$scratch/up.toon" || {
  printf 'error: public Kast did not report semantic readiness\n' >&2
  exit 1
}
grep -Eq '^backend: headless$' "$scratch/up.toon" || {
  printf 'error: public Kast did not select the headless runtime\n' >&2
  exit 1
}

# The public surface intentionally hides process identity. Use the private
# control surface only to take the exact comparison snapshot.
"$kastctl_binary" --output json status \
  --workspace-root "$workspace" \
  --backend headless >"$scratch/status.json"

mkdir -p "$(dirname -- "$identity_file")"
python3 - "$scratch/status.json" "$identity_file" "$workspace" "$scenario" <<'PY'
import json
import os
import sys
from pathlib import Path

status_path = Path(sys.argv[1])
identity_path = Path(sys.argv[2])
workspace = sys.argv[3]
scenario = sys.argv[4]

status = json.loads(status_path.read_text())
selected = status.get("selected") or {}
descriptor = selected.get("descriptor") or {}
runtime = selected.get("runtimeStatus") or {}

if descriptor.get("workspaceRoot") != workspace:
    raise SystemExit("status selected a different canonical workspace root")
if descriptor.get("backendName") != "headless":
    raise SystemExit("status selected a non-headless backend")
if runtime.get("state") != "READY" or runtime.get("healthy") is not True:
    raise SystemExit("headless runtime is not healthy and READY")
if runtime.get("referenceIndexReady") is not True:
    raise SystemExit("reference indexing is not ready")

identity_keys = (
    "workspaceRoot",
    "backendName",
    "backendVersion",
    "runtimeInstanceId",
    "processStartEpochMillis",
    "ownerUid",
    "socketPath",
    "socketFileIdentity",
    "pid",
)
identity = {key: descriptor.get(key) for key in identity_keys}
missing = [key for key, value in identity.items() if value in (None, "", 0)]
if missing:
    raise SystemExit(f"headless descriptor has incomplete identity: {', '.join(missing)}")

if identity_path.exists():
    baseline = json.loads(identity_path.read_text())
    if baseline.get("identity") != identity:
        raise SystemExit("foreground state changed the exact headless runtime identity")
    scenarios = sorted(set(baseline.get("scenarios", [])) | {scenario})
else:
    scenarios = [scenario]

payload = {"identity": identity, "scenarios": scenarios}
temporary = identity_path.with_suffix(identity_path.suffix + ".tmp")
temporary.write_text(json.dumps(payload, indent=2) + "\n")
os.replace(temporary, identity_path)

print(identity["runtimeInstanceId"])
print(",".join(scenarios))
PY

printf 'PASS %s: exact-root headless runtime stayed READY\n' "$scenario"
printf 'Identity evidence: %s\n' "$identity_file"
