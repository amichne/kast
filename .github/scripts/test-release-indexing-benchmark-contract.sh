#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
release="$repo_root/.github/workflows/release.yml"
runner="$repo_root/scripts/benchmark-real-repositories.sh"
cli_root="$repo_root/cli-rs/src/interface/cli/root.rs"
cli_config="$repo_root/cli-rs/src/interface/cli/config.rs"
dispatch="$repo_root/cli-rs/src/interface/entrypoint/dispatch.rs"
cli_model="$repo_root/cli-rs/src/configuration/config/model.rs"
idea_indexer="$repo_root/backend-idea/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt"
indexer="$repo_root/index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt"
schema="$repo_root/index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/schema/SourceIndexSchemaTables.kt"

require() {
  local file="$1" text="$2" message="$3"
  grep -Fq -- "$text" "$file" || { printf 'error: %s\n' "$message" >&2; exit 1; }
}

[[ -x "$runner" ]] || {
  printf '%s\n' 'error: real-repository benchmark runner must be executable' >&2
  exit 1
}

require "$release" 'real-repository-indexing:' 'release workflow must own the real-repository indexing gate'
require "$release" 'fail-fast: false' 'repository matrix failures must not cancel remaining repositories'
require "$release" 'ktorio/ktor.git' 'release gate must include Ktor'
require "$release" 'spring-projects/spring-boot.git' 'release gate must include Spring Boot'
require "$release" 'square/okhttp.git' 'release gate must include a Kotlin Multiplatform integration repository'
# shellcheck disable=SC2016 # GitHub expression is intentionally matched literally.
require "$release" 'linux-headless-tarball-${{ github.run_id }}' 'release gate must test the built release runtime'
require "$release" 'needs.real-repository-indexing.result == '\''success'\''' 'release publication must require the repository gate'
require "$runner" 'kast config list' 'benchmark must capture effective workspace configuration'
require "$runner" 'kast config set indexing.relationships.parallelism 2' 'benchmark must exercise relationship indexing configuration'

require "$cli_root" 'Config(ConfigArgs)' 'CLI must expose the config command family'
require "$cli_config" 'List(ConfigWorkspaceArgs)' 'config must list effective workspace state'
require "$cli_config" 'Set(ConfigSetArgs)' 'config must set one workspace field non-interactively'
require "$cli_config" 'Unset(ConfigUnsetArgs)' 'config must unset one workspace field non-interactively'
require "$dispatch" 'Command::Config(args)' 'config commands must be dispatched'
require "$cli_model" 'pub relationships: RelationshipIndexingConfig' 'effective config must name relationship indexing directly'
require "$idea_indexer" 'indexSymbolRelationships(' 'IDEA orchestration must name the compiler-resolved operation'
require "$indexer" 'onFilesIndexed(successfulPaths)' 'failed scans must not be reported as indexed'
require "$schema" 'relationship_index_status TEXT NOT NULL' 'module progress must name relationship indexing state'

printf '%s\n' 'release indexing benchmark contract passed'
