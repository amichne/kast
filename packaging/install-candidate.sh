#!/usr/bin/env bash
set -euo pipefail
script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
command -v uv >/dev/null 2>&1 || { printf '%s\n' 'kast: candidate installation needs uv' >&2; exit 1; }
exec uv run --locked --script "$script_directory/managed_installation.py" "$@"
