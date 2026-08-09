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
  grep --fixed-strings --quiet -- "$2" "${repo_root}/$1" || {
    echo "$1 must contain: $2" >&2
    exit 1
  }
}

require_not_contains() {
  ! grep --fixed-strings --quiet -- "$2" "${repo_root}/$1" || {
    echo "$1 must not contain: $2" >&2
    exit 1
  }
}

require_graphviz_output_scripts() {
  node - "${repo_root}/package.json" <<'NODE'
const fs = require('node:fs')

const packagePath = process.argv[2]
const packageJson = JSON.parse(fs.readFileSync(packagePath, 'utf8'))
const scripts = packageJson.scripts ?? {}

const tokens = (name) => {
  const command = scripts[name]
  if (typeof command !== 'string') {
    throw new Error(`package.json must define ${name}`)
  }
  return new Set(command.trim().split(/\s+/))
}

for (const name of ['diagrams:embed', 'diagrams:build']) {
  if (!tokens(name).has('--use-dot')) {
    throw new Error(`${name} must use the installed Graphviz dot binary`)
  }
}

const validation = tokens('diagrams:validate')
if (!validation.has('--no-layout')) {
  throw new Error('diagrams:validate must remain layout-free')
}
if (validation.has('--use-dot')) {
  throw new Error('diagrams:validate must not select a layout engine when layout is disabled')
}
NODE
}

require_single_shared_refresh_route() {
  local refresh_block
  local shared_route_line
  local branch_line
  local count

  refresh_block="$(sed -n '/^  dynamic view refresh-lifecycle {$/,/^  }$/p' \
    "${repo_root}/docs/architecture/flows.c4")"
  [[ -n "$refresh_block" ]] || {
    echo "docs/architecture/flows.c4 must define refresh-lifecycle" >&2
    exit 1
  }

  count="$(grep --fixed-strings --count -- \
    'kast.cli -> kast.agent' <<<"$refresh_block")"
  [[ "$count" -eq 1 ]] || {
    echo "refresh-lifecycle must contain one shared CLI-to-agent route; found $count" >&2
    exit 1
  }

  shared_route_line="$(grep --fixed-strings --line-number --max-count=1 -- \
    'kast.server.router -> kast.server.orchestrator' <<<"$refresh_block" | cut -d: -f1)"
  branch_line="$(grep --fixed-strings --line-number --max-count=1 -- \
    'alt {' <<<"$refresh_block" | cut -d: -f1)"
  [[ -n "$shared_route_line" && -n "$branch_line" && "$shared_route_line" -lt "$branch_line" ]] || {
    echo "refresh-lifecycle must route through the orchestrator before selecting a refresh operation" >&2
    exit 1
  }
}

for file in \
  .github/workflows/docs.yml \
  package.json \
  docs/architecture/likec4.config.json \
  docs/architecture/specification.c4 \
  docs/architecture/model.c4 \
  docs/architecture/deployment.c4 \
  docs/architecture/views.c4 \
  docs/architecture/flows.c4 \
  docs/architecture/likec4-views.mjs; do
  require_file "$file"
done

require_contains package.json '"diagrams:dev"'
require_contains package.json '"diagrams:validate"'
require_contains package.json '"diagrams:embed"'
require_contains package.json '"diagrams:build"'
require_graphviz_output_scripts
require_contains .github/workflows/docs.yml 'sudo apt-get install --yes --no-install-recommends graphviz'
require_contains .github/workflows/docs.yml 'command -v dot'
require_contains .github/workflows/docs.yml 'command -v unflatten'
require_contains zensical.toml 'docs_dir = "docs/public"'
require_not_contains zensical.toml 'path = "architecture/likec4-views.mjs"'
require_contains docs/architecture/views.c4 'view system-landscape'
require_contains docs/architecture/views.c4 'view runtime-components'
require_contains docs/architecture/views.c4 'view indexing-landscape'
require_contains docs/architecture/views.c4 'view indexer-pipeline'
require_contains docs/architecture/views.c4 'view retained-evidence'
require_contains docs/architecture/deployment.c4 'deployment view indexer-runtime'
require_contains docs/architecture/deployment.c4 'Kast reuses an eligible exact-root process or creates one isolated indexer.'
require_contains docs/architecture/flows.c4 'dynamic view indexer-admission'
require_contains docs/architecture/flows.c4 'dynamic view compiler-read'
require_contains docs/architecture/flows.c4 'dynamic view semantic-mutation'
require_contains docs/architecture/flows.c4 'dynamic view reference-indexing'
require_contains docs/architecture/flows.c4 'dynamic view sqlite-pipeline'
require_contains docs/architecture/flows.c4 'dynamic view refresh-lifecycle'
require_contains docs/architecture/flows.c4 'variant sequence'
require_contains docs/architecture/flows.c4 'navigateTo indexer-admission'
require_single_shared_refresh_route
require_contains docs/architecture/views.c4 'navigateTo reference-indexing'
require_contains docs/architecture/views.c4 'navigateTo sqlite-pipeline'
require_contains docs/architecture/views.c4 'navigateTo refresh-lifecycle'
require_contains docs/architecture/views.c4 'style'
require_contains docs/architecture/model.c4 'metadata'
for term in \
  'PSI' \
  'K2 Frontend' \
  'Analysis API' \
  'FIR' \
  'ReferenceIndexer' \
  'SemanticGraphWriter' \
  'StringInterningCodec' \
  'PRAGMA main.data_version' \
  'semantic_files' \
  'reference occurrences' \
  'refresh_status'; do
  require_contains docs/architecture/model.c4 "$term"
done
require_contains docs/internal/system-flow.md '<kast-view'
require_contains docs/internal/system-flow.md 'dynamic-variant="sequence"'
require_contains docs/internal/system-flow.md 'npm run diagrams:dev'
require_contains docs/internal/system-flow.md 'npm run diagrams:embed'

echo "LikeC4 architecture contract passed"
