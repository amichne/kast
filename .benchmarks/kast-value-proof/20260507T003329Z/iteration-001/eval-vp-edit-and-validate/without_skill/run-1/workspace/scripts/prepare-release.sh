#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

VERSION_CHOICE="${1:-}"

if [[ -n "$VERSION_CHOICE" ]]; then
    exec "$SCRIPT_DIR/publish-on-rails.sh" --target release --version-choice "$VERSION_CHOICE"
fi

exec "$SCRIPT_DIR/publish-on-rails.sh" --target release
