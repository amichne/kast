#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel 2>/dev/null || (cd -- "${SCRIPT_DIR}/../.." && pwd))"
RESOLVE_SCRIPT="${REPO_ROOT}/.github/extensions/kast/scripts/resolve-kast.sh"
if [[ -x "${RESOLVE_SCRIPT}" ]]; then
    exec bash "${RESOLVE_SCRIPT}"
fi

resolve_absolute_path() {
    local path="$1"
    local dir
    local base
    dir="$(cd -- "$(dirname -- "${path}")" && pwd)"
    base="$(basename -- "${path}")"
    printf '%s/%s\n' "${dir}" "${base}"
}

read_config_binary_path() {
    local config_file="$1"
    [[ -f "${config_file}" ]] || return 1
    awk '
        /^[[:space:]]*\[cli\][[:space:]]*$/ { in_cli = 1; next }
        /^[[:space:]]*\[/ { in_cli = 0 }
        in_cli && /^[[:space:]]*binaryPath[[:space:]]*=/ {
            line = $0
            sub(/^[^"]*"/, "", line)
            sub(/".*$/, "", line)
            print line
            exit
        }
    ' "${config_file}"
}

config_dir="${KAST_CONFIG_HOME:-${HOME}/.config/kast}"
config_binary="$(read_config_binary_path "${config_dir}/config.toml" || true)"
if [[ -n "${config_binary}" && -x "${config_binary}" ]]; then
    resolve_absolute_path "${config_binary}"
    exit 0
fi

if [[ -x "${HOME}/.kast/bin/kast" ]]; then
    resolve_absolute_path "${HOME}/.kast/bin/kast"
    exit 0
fi

if command -v kast >/dev/null 2>&1; then
    resolve_absolute_path "$(command -v kast)"
    exit 0
fi

for candidate in \
    "${REPO_ROOT}/target/debug/kast" \
    "${REPO_ROOT}/target/release/kast"; do
    if [[ -x "${candidate}" ]]; then
        resolve_absolute_path "${candidate}"
        exit 0
    fi
done

echo "Unable to resolve the Rust kast CLI path. Install kast on PATH or build kast-rs first." >&2
exit 1
