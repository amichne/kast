#!/usr/bin/env bash
set -euo pipefail

_sha256_hex() {
    local input="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "${input}" | sha256sum | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        printf '%s' "${input}" | shasum -a 256 | awk '{print $1}'
    else
        printf '%s' "${input}" | python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.read().encode()).hexdigest())'
    fi
}

hook_state_file() {
    local repo_root="$1"
    local session_key
    session_key="$(_sha256_hex "${repo_root}")"
    printf '%s/copilot-hook-paths-%s.txt\n' "${TMPDIR:-/tmp}" "${session_key}"
}

hook_skill_state_file() {
    local repo_root="$1"
    local session_key
    session_key="$(_sha256_hex "${repo_root}")"
    printf '%s/copilot-hook-required-skills-%s.txt\n' "${TMPDIR:-/tmp}" "${session_key}"
}
