#!/usr/bin/env bash
set -euo pipefail
exec uv run --locked --script "$(dirname "${BASH_SOURCE[0]}")/publish_developer.py" "$@"
