#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$file_path" || die "${description}: missing '${expected}'"
}

repo_root="$(resolve_repo_root)"
verifier="${repo_root}/scripts/verify-devin-snapshot-build.sh"
[[ -f "$verifier" ]] || die "Verifier script is missing: $verifier"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-devin-snapshot-test.XXXXXX")"
server_pid=""
cleanup() {
  if [[ -n "${server_pid:-}" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" >/dev/null 2>&1 || true
  fi
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

server_script="${scratch_dir}/fake-devin-api.py"
port_file="${scratch_dir}/port"
request_log="${scratch_dir}/requests.jsonl"
stdout_file="${scratch_dir}/stdout.txt"
stderr_file="${scratch_dir}/stderr.txt"

cat > "$server_script" <<'PY'
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

port_file = Path(sys.argv[1])
request_log = Path(sys.argv[2])
build_ok_gets = 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        return

    def record(self):
        entry = {
            "method": self.command,
            "path": self.path,
            "authorization": self.headers.get("Authorization", ""),
        }
        with request_log.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, sort_keys=True) + "\n")

    def respond(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def require_auth(self):
        if self.headers.get("Authorization") != "Bearer test-token":
            self.respond(401, {"error": "unauthorized"})
            return False
        return True

    def do_POST(self):
        self.record()
        if not self.require_auth():
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        if self.path != "/v3beta1/organizations/acme/snapshot-setup/builds":
            self.respond(404, {"error": "not found"})
            return
        if json.loads(body or "{}") != {}:
            self.respond(400, {"error": "expected empty JSON object"})
            return
        self.respond(201, {"build_id": "build-ok", "status": "pending"})

    def do_GET(self):
        global build_ok_gets
        self.record()
        if not self.require_auth():
            return
        if self.path == "/v3beta1/organizations/acme/snapshot-setup/builds/build-ok":
            build_ok_gets += 1
            status = "running" if build_ok_gets == 1 else "succeeded"
            self.respond(200, {"build_id": "build-ok", "status": status})
            return
        if self.path == "/v3beta1/organizations/acme/snapshot-setup/builds/build-failed":
            self.respond(200, {"build_id": "build-failed", "status": "failed"})
            return
        self.respond(404, {"error": "not found"})


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
port_file.write_text(str(server.server_address[1]), encoding="utf-8")
server.serve_forever()
PY

python3 "$server_script" "$port_file" "$request_log" &
server_pid="$!"
for _ in {1..50}; do
  [[ -s "$port_file" ]] && break
  sleep 0.1
done
[[ -s "$port_file" ]] || die "Fake Devin API server did not start"

api_base="http://127.0.0.1:$(<"$port_file")/v3beta1"

"$verifier" \
  --api-base "$api_base" \
  --org-id acme \
  --build-id build-ok \
  --dry-run \
  >"$stdout_file" \
  2>"$stderr_file"
require_contains "$stdout_file" "Would poll Devin snapshot build" "dry-run output must name polling"
[[ ! -e "$request_log" || ! -s "$request_log" ]] \
  || die "dry-run unexpectedly called the fake Devin API"

if env -u DEVIN_SERVICE_USER_TOKEN -u DEVIN_API_TOKEN "$verifier" \
  --api-base "$api_base" \
  --org-id acme \
  --build-id build-ok \
  >"$stdout_file" \
  2>"$stderr_file"; then
  die "verifier unexpectedly succeeded without a Devin token"
fi
require_contains "$stderr_file" "Set DEVIN_SERVICE_USER_TOKEN" "missing token error must name expected environment variable"

DEVIN_SERVICE_USER_TOKEN=test-token "$verifier" \
  --api-base "$api_base" \
  --org-id acme \
  --trigger \
  --timeout-seconds 5 \
  --poll-seconds 1 \
  >"$stdout_file" \
  2>"$stderr_file"
require_contains "$stdout_file" "Triggered Devin snapshot build build-ok" "trigger path must report the build id"
require_contains "$stdout_file" "Devin snapshot build build-ok succeeded" "trigger path must poll to success"
if grep -R -Fq -- "test-token" "$stdout_file" "$stderr_file"; then
  die "verifier leaked the Devin token in trigger output"
fi

if DEVIN_API_TOKEN=test-token "$verifier" \
  --api-base "$api_base" \
  --org-id acme \
  --build-id build-failed \
  --timeout-seconds 5 \
  --poll-seconds 1 \
  >"$stdout_file" \
  2>"$stderr_file"; then
  die "verifier unexpectedly succeeded for a failed Devin build"
fi
require_contains "$stderr_file" "status failed" "failed build path must report terminal failure"
if grep -R -Fq -- "test-token" "$stdout_file" "$stderr_file"; then
  die "verifier leaked the Devin token in failure output"
fi

python3 - "$request_log" <<'PY'
import json
import sys
from pathlib import Path

entries = [
    json.loads(line)
    for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
    if line.strip()
]
if not entries:
    raise SystemExit("fake Devin API did not receive any requests")
if any(entry.get("authorization") != "Bearer test-token" for entry in entries):
    raise SystemExit("fake Devin API did not receive the expected Authorization header")
paths = [entry["path"] for entry in entries]
expected_paths = {
    "/v3beta1/organizations/acme/snapshot-setup/builds",
    "/v3beta1/organizations/acme/snapshot-setup/builds/build-ok",
    "/v3beta1/organizations/acme/snapshot-setup/builds/build-failed",
}
missing = sorted(expected_paths - set(paths))
if missing:
    raise SystemExit(f"fake Devin API did not receive expected paths: {missing}")
PY

printf '%s\n' "Devin snapshot build verifier contract passed"
