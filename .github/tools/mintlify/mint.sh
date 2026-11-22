#!/usr/bin/env bash
# Run the locked Mintlify CLI against docs/public, for example `mint.sh validate`.
#
# Exit 2 with MINTLIFY_NOT_INSTALLED when `mise run docs:mintlify-install` has not run.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
mint="$root/.github/tools/mintlify/node_modules/.bin/mint"
if [ ! -x "$mint" ]; then
  echo "MINTLIFY_NOT_INSTALLED: $mint is missing; run 'mise run docs:mintlify-install'" >&2
  exit 2
fi
cd "$root/docs/public"
exec "$mint" "$@"
