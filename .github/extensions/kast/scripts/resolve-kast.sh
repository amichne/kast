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

if [[ -n "${KAST_CLI_PATH:-}" && -x "${KAST_CLI_PATH}" ]]; then
    resolve_absolute_path "${KAST_CLI_PATH}"
    exit 0
fi

if command -v kast >/dev/null 2>&1; then
    resolve_absolute_path "$(command -v kast)"
    exit 0
fi

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

echo "Unable to resolve Kast CLI path. Set KAST_CLI_PATH or install kast on PATH." >&2
exit 1
