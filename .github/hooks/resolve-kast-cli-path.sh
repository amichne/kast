#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"

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

for candidate in \
    "${REPO_ROOT}/kast-cli/build/scripts/kast-cli" \
    "${REPO_ROOT}/dist/cli/kast-cli"; do
    if [[ -x "${candidate}" ]]; then
        resolve_absolute_path "${candidate}"
        exit 0
    fi
done

echo "Unable to resolve Kast CLI path. Set KAST_CLI_PATH or build/install kast first." >&2
exit 1
