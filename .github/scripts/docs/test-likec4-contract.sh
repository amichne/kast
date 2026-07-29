#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

require_file() {
  [[ -f "${repo_root}/$1" ]] || {
    echo "missing LikeC4 file: $1" >&2
    exit 1
  }
}

require_contains() {
  rg --fixed-strings --quiet "$2" "${repo_root}/$1" || {
    echo "$1 must contain: $2" >&2
    exit 1
  }
}

for file in \
  package.json \
  docs/architecture/likec4.config.json \
  docs/architecture/specification.c4 \
  docs/architecture/model.c4 \
  docs/architecture/deployment.c4 \
  docs/architecture/views.c4 \
  docs/architecture/flows.c4; do
  require_file "$file"
done

require_contains package.json '"diagrams:dev"'
require_contains package.json '"diagrams:validate"'
require_contains package.json '"diagrams:build"'
require_contains docs/architecture/views.c4 'view system-landscape'
require_contains docs/architecture/views.c4 'view runtime-components'
require_contains docs/architecture/deployment.c4 'deployment view macos-runtime'
require_contains docs/architecture/flows.c4 'dynamic view compiler-read'
require_contains docs/architecture/flows.c4 'dynamic view semantic-mutation'
require_contains docs/architecture/flows.c4 'variant sequence'
require_contains docs/architecture/flows.c4 'navigateTo runtime-components'
require_contains docs/architecture/views.c4 'style'
require_contains docs/architecture/model.c4 'metadata'
require_contains docs/internal/system-flow.md '```likec4-view'

echo "LikeC4 architecture contract passed"
