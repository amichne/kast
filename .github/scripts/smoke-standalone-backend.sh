#!/usr/bin/env bash
# Smoke test: start backend-standalone directly via `kast internal daemon-run`
# and validate that it responds to RPC on a Unix domain socket.
set -euo pipefail

log() {
  printf '%s\n' "$*" >&2
}

die() {
  log "error: $*"
  exit 1
}

if [[ $# -ne 1 ]]; then
  die "Usage: $0 /absolute/path/to/kast"
fi

readonly KAST_CMD="$1"
[[ -x "$KAST_CMD" ]] || die "Kast command is not executable: $KAST_CMD"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-backend-smoke.XXXXXX")"
workspace_dir="${tmp_dir}/workspace"
socket_path="${tmp_dir}/kast.sock"
daemon_pid=""

cleanup() {
  if [[ -n "$daemon_pid" ]]; then
    kill "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" 2>/dev/null || true
  fi
  rm -rf "$tmp_dir"
}

trap cleanup EXIT

mkdir -p "${workspace_dir}/src/main/kotlin/sample"

cat > "${workspace_dir}/src/main/kotlin/sample/Hello.kt" <<'KT'
package sample

fun hello(): String = "world"
KT

# Start the backend directly using `internal daemon-run`.
# This exercises the backend-standalone startup path without the full workspace
# ensure / daemon management layer.
"$KAST_CMD" internal daemon-run \
  --workspace-root="$workspace_dir" \
  --socket-path="$socket_path" \
  --request-timeout-ms=180000 \
  >"${tmp_dir}/daemon-stdout.log" 2>"${tmp_dir}/daemon-stderr.log" &
daemon_pid=$!

# Wait for the socket to appear (the daemon creates it once listening).
startup_timeout=120
for i in $(seq 1 "$startup_timeout"); do
  if [[ -S "$socket_path" ]]; then
    break
  fi
  if ! kill -0 "$daemon_pid" 2>/dev/null; then
    log "Daemon exited before socket was created."
    log "stdout:"
    cat "${tmp_dir}/daemon-stdout.log" >&2 || true
    log "stderr:"
    cat "${tmp_dir}/daemon-stderr.log" >&2 || true
    die "Backend daemon exited prematurely"
  fi
  if (( i % 10 == 0 )); then
    log "Waiting for socket (${i}/${startup_timeout}s)..."
  fi
  sleep 1
done

[[ -S "$socket_path" ]] || die "Socket was not created within ${startup_timeout}s at $socket_path"
log "Backend socket ready at $socket_path"

# Send a capabilities RPC request over the Unix domain socket.
capabilities_response="$(
  python3 - "$socket_path" <<'PY'
import json
import socket
import sys

socket_path = sys.argv[1]
request = json.dumps({
    "jsonrpc": "2.0",
    "method": "capabilities",
    "id": "smoke-1",
})

sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.settimeout(60)
sock.connect(socket_path)
sock.sendall((request + "\n").encode("utf-8"))

# Read until we get a complete JSON line.
buf = b""
while True:
    chunk = sock.recv(4096)
    if not chunk:
        break
    buf += chunk
    if b"\n" in buf:
        break

sock.close()
line = buf.split(b"\n", 1)[0]
print(line.decode("utf-8"))
PY
)"

[[ -n "$capabilities_response" ]] || die "Empty response from capabilities RPC"

# Validate the capabilities response.
python3 - "$capabilities_response" <<'PY'
import json
import sys

raw = sys.argv[1]
envelope = json.loads(raw)

assert envelope.get("jsonrpc") == "2.0", f"unexpected jsonrpc version: {envelope}"
assert "result" in envelope, f"expected 'result' in response: {envelope}"

caps = envelope["result"]
assert caps["backendName"] == "standalone", f"unexpected backendName: {caps}"
assert "RESOLVE_SYMBOL" in caps["readCapabilities"], f"missing RESOLVE_SYMBOL: {caps}"
assert "FIND_REFERENCES" in caps["readCapabilities"], f"missing FIND_REFERENCES: {caps}"
assert "DIAGNOSTICS" in caps["readCapabilities"], f"missing DIAGNOSTICS: {caps}"
assert "RENAME" in caps["mutationCapabilities"], f"missing RENAME: {caps}"

print(f"Standalone backend capabilities verified: {len(caps['readCapabilities'])} read, {len(caps['mutationCapabilities'])} mutation")
PY

# Send a health check RPC.
health_response="$(
  python3 - "$socket_path" <<'PY'
import json
import socket
import sys

socket_path = sys.argv[1]
request = json.dumps({
    "jsonrpc": "2.0",
    "method": "health",
    "id": "smoke-2",
})

sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.settimeout(60)
sock.connect(socket_path)
sock.sendall((request + "\n").encode("utf-8"))

buf = b""
while True:
    chunk = sock.recv(4096)
    if not chunk:
        break
    buf += chunk
    if b"\n" in buf:
        break

sock.close()
line = buf.split(b"\n", 1)[0]
print(line.decode("utf-8"))
PY
)"

[[ -n "$health_response" ]] || die "Empty response from health RPC"

python3 - "$health_response" <<'PY'
import json
import sys

raw = sys.argv[1]
envelope = json.loads(raw)

assert envelope.get("jsonrpc") == "2.0", f"unexpected jsonrpc version: {envelope}"
assert "result" in envelope, f"expected 'result' in health response: {envelope}"

health = envelope["result"]
assert health.get("status") == "ok", f"unexpected health status: {health}"
PY

# Clean shutdown: kill daemon and verify it exits cleanly.
kill "$daemon_pid" 2>/dev/null || true
wait "$daemon_pid" 2>/dev/null || true
daemon_pid=""

log "Standalone backend smoke test passed"
