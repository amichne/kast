#!/usr/bin/env bash
# Click through Get started on desktop and mobile in the exact pinned Chrome.
#
# Installs the locked rendered tooling, downloads Chrome through the locked Mintlify browser
# installer, and runs the owned-preview reader journey. The installer prints
# `chrome@<build> <executable>`; the journey uses that executable on every platform.
# Exit 2 with a typed reason when a precondition is missing. Extra arguments go to run.py.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
chrome="chrome@155.0.8059.84"
installer=".github/tools/mintlify/node_modules/@puppeteer/browsers/lib/cjs/main-cli.js"
cd "$root"

if [ ! -f "$installer" ]; then
  echo "READER_JOURNEY_MINTLIFY_NOT_INSTALLED: $installer is missing; run 'mise run docs:mintlify-install'" >&2
  exit 2
fi

npm ci --prefix docs/internal/tooling/rendered --ignore-scripts --no-audit --no-fund
installed="$(node "$installer" install "$chrome" --path build/documentation/browsers)"
printf '%s\n' "$installed"
executable="$(printf '%s\n' "$installed" | sed -n "s/^$chrome //p" | tail -n 1)"
if [ -z "$executable" ] || [ ! -x "$executable" ]; then
  echo "READER_JOURNEY_BROWSER_UNAVAILABLE: the installer reported no executable for $chrome" >&2
  exit 2
fi

exec uv run --locked --project docs/internal/tooling python docs/internal/tooling/rendered/run.py \
  --preview --reader-journey \
  --browser-executable "$executable" \
  --output build/reports/documentation/reader-journey \
  "$@"
