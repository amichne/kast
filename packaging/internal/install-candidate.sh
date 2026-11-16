#!/bin/bash
set -euo pipefail
script_directory="$(cd -- "${BASH_SOURCE[0]%/*}" && pwd -P)"
python="$script_directory/private-python/bin/python3.12"
[[ -x "$python" && ! -L "$python" ]] || { printf '%s\n' 'kast: pinned private interpreter unavailable; use the public bootstrap' >&2; exit 1; }
if [[ "${1:-}" == --remove-product ]]; then
  exec "$python" -I -B "$script_directory/bootstrap_entry.py" "$@"
fi
exec "$python" -I -B "$script_directory/bootstrap_entry.py" \
  --private-python-archive "$script_directory/private-python.tar.gz" \
  --private-dependency "$script_directory/defusedxml.whl" "$@"
