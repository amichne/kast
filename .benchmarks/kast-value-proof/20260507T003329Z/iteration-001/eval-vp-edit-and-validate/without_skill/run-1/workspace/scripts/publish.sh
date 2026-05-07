#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

TARGET="${1:-}"

if [[ -z "$TARGET" ]]; then
    echo "Usage: ./scripts/publish.sh {local|snapshot|release|github}" >&2
    echo "Or use: make publish" >&2
    exit 1
fi

case "$TARGET" in
    local|snapshot|release|github)
        ;;
    *)
        echo "Invalid target '$TARGET'. Expected local|snapshot|release|github." >&2
        exit 1
        ;;
esac

make "publish-run-$TARGET"
