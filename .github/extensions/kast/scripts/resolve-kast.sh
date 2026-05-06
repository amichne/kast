#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

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

for command_name in kast kast-cli; do
    if command -v "${command_name}" >/dev/null 2>&1; then
        resolve_absolute_path "$(command -v "${command_name}")"
        exit 0
    fi
done

search_dir="${SCRIPT_DIR}"
for _ in 1 2 3 4 5 6; do
    for candidate in \
        "${search_dir}/kast-cli/build/scripts/kast-cli" \
        "${search_dir}/dist/cli/kast-cli"; do
        if [[ -x "${candidate}" ]]; then
            resolve_absolute_path "${candidate}"
            exit 0
        fi
    done
    search_dir="$(cd -- "${search_dir}/.." && pwd)"
done

config_dir="${KAST_CONFIG_HOME:-${HOME}/.config/kast}"
config_binary="$(read_config_binary_path "${config_dir}/config.toml" || true)"
if [[ -n "${config_binary}" && -x "${config_binary}" ]]; then
    resolve_absolute_path "${config_binary}"
    exit 0
fi

# Recovery: standard user install location may not be on PATH in non-interactive shells
if [[ -x "${HOME}/.local/bin/kast" ]]; then
    resolve_absolute_path "${HOME}/.local/bin/kast"
    exit 0
fi

echo "Unable to resolve Kast CLI path. Install kast on PATH or build the local wrapper first." >&2
exit 1
