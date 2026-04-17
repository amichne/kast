#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"
LOCAL_BUILD_KAST="${REPO_ROOT}/kast/build/scripts/kast"
LOCAL_PORTABLE_KAST="${REPO_ROOT}/kast/build/portable-dist/kast/kast"

if [[ -x "${LOCAL_BUILD_KAST}" ]]; then
    KAST="${LOCAL_BUILD_KAST}"
elif [[ -x "${LOCAL_PORTABLE_KAST}" ]]; then
    KAST="${LOCAL_PORTABLE_KAST}"
else
    KAST="$(bash "${SCRIPT_DIR}/resolve-kast.sh")"
fi

export KAST_CLI_PATH="${KAST}"

printf 'Resolved kast CLI: %s\n' "${KAST}"
"${KAST}" workspace ensure --workspace-root="${REPO_ROOT}"
