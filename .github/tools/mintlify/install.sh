#!/usr/bin/env bash
# Install the locked Mintlify CLI with empty npm user and global configuration.
#
# The npm configuration and cache live in the runner's scratch directory, so a developer's npm
# settings cannot change what CI resolves. Extra arguments go to `npm ci`.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
scratch="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
scratch="${scratch%/}"

export NPM_CONFIG_USERCONFIG="$scratch/kast-mint-user.npmrc"
export NPM_CONFIG_GLOBALCONFIG="$scratch/kast-mint-global.npmrc"
export NPM_CONFIG_CACHE="$scratch/kast-mint-npm-cache"
export NPM_CONFIG_UPDATE_NOTIFIER=false

: > "$NPM_CONFIG_USERCONFIG"
: > "$NPM_CONFIG_GLOBALCONFIG"
cd "$root"
exec npm ci --prefix .github/tools/mintlify --ignore-scripts --no-audit --no-fund "$@"
