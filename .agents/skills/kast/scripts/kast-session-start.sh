#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
KAST_BIN="$(bash "${SCRIPT_DIR}/resolve-kast.sh")"

printf 'KAST_BIN=%q\n' "${KAST_BIN}"
