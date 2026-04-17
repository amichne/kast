#!/usr/bin/env bash
set -euo pipefail

hook_state_file() {
    local repo_root="$1"
    local session_key
    session_key="$(
        python3 - "${repo_root}" <<'PY'
import hashlib
import sys

print(hashlib.sha256(sys.argv[1].encode("utf-8")).hexdigest())
PY
    )"
    printf '%s/copilot-hook-paths-%s.txt\n' "${TMPDIR:-/tmp}" "${session_key}"
}
