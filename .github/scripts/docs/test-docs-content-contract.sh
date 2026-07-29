#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
docs_root="${repo_root}/docs"
readme="${repo_root}/README.md"

require_contains() {
  grep -Fq -- "$2" "$1" || die "missing '$2' in $1"
}

require_absent() {
  [[ ! -e "$1" ]] || die "obsolete public path exists: $1"
}

require_not_contains() {
  ! grep -R -Fq --exclude-dir=internal --include='*.md' -- "$2" "$1" || die "found '$2' under $1"
}

expected_pages=(
  "explanation/architecture.md"
  "explanation/compiler-evidence.md"
  "explanation/repository-intelligence.md"
  "how-to/explore-kotlin-code.md"
  "how-to/install-or-update.md"
  "how-to/maintain-repository-intelligence.md"
  "how-to/plan-safe-edits.md"
  "how-to/troubleshoot.md"
  "index.md"
  "reference/cli.md"
  "reference/codex-plugin.md"
  "tutorials/first-compiler-backed-task.md"
)
actual_pages="$(find "$docs_root" -path "${docs_root}/internal" -prune -o -type f -name '*.md' -print | sed "s#${docs_root}/##" | sort)"
expected_page_lines="$(printf '%s\n' "${expected_pages[@]}" | sort)"
[[ "$actual_pages" == "$expected_page_lines" ]] || {
  printf 'expected pages:\n%s\nactual pages:\n%s\n' "$expected_page_lines" "$actual_pages" >&2
  die "public Markdown set differs from the Codex workstation journey"
}

require_absent "${docs_root}/privacy.md"
require_absent "${docs_root}/terms.md"
require_absent "${docs_root}/install"
require_absent "${docs_root}/use"
require_absent "${docs_root}/design"
require_absent "${docs_root}/assets/demo"

installer='/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"'
require_contains "$readme" "$installer"
require_contains "$readme" "kast setup"
require_contains "$readme" "prior active release usable"
require_contains "$readme" "amichne/kast-marketplace"
require_contains "${docs_root}/how-to/install-or-update.md" "$installer"
require_contains "${docs_root}/how-to/install-or-update.md" "./gradlew refreshDevelopmentMachine"
require_contains "${docs_root}/how-to/install-or-update.md" "current/bin/kast"
require_contains "${docs_root}/tutorials/first-compiler-backed-task.md" "IdeaIndexSemanticAdmission"
require_contains "${docs_root}/how-to/explore-kotlin-code.md" "coverage is complete or limited"
require_contains "${docs_root}/how-to/plan-safe-edits.md" "one exact compiler identity"
for command in help version context config setup ready start status stop demo rpc developer agent; do
  require_contains "${docs_root}/reference/cli.md" "\`kast ${command}"
done
require_contains "${docs_root}/reference/cli.md" '`toon`'
require_contains "${docs_root}/reference/codex-plugin.md" 'tracks its `main` branch independently'
require_contains "${docs_root}/reference/codex-plugin.md" '`kast-query` skill'
require_contains "${docs_root}/reference/codex-plugin.md" '`kast-change` skill'
require_contains "${docs_root}/reference/codex-plugin.md" '`kast-codex` hook'
require_contains "${docs_root}/explanation/architecture.md" "exact workspace"
require_contains "${docs_root}/explanation/compiler-evidence.md" "scope fingerprint"
require_contains "${docs_root}/explanation/repository-intelligence.md" "Incomplete positive answers fail closed"
require_contains "${docs_root}/explanation/repository-intelligence.md" "Precomputed labels are retrieval-only"
require_contains "${docs_root}/how-to/maintain-repository-intelligence.md" "Recover compiler graph evidence"
require_contains "${docs_root}/how-to/maintain-repository-intelligence.md" "exact source identity"
require_contains "${docs_root}/how-to/troubleshoot.md" 'Do not edit `current`'
require_contains "${repo_root}/requirements-docs.txt" "zensical==0.0.51"
require_contains "${repo_root}/zensical.toml" 'extra_css = ["stylesheets/extra.css"]'
require_contains "${repo_root}/zensical.toml" "[project.validation]"
require_contains "${repo_root}/zensical.toml" "invalid_links = true"
require_contains "${repo_root}/zensical.toml" "invalid_link_anchors = true"
require_contains "${docs_root}/stylesheets/extra.css" ".md-typeset__table"
require_contains "${docs_root}/stylesheets/extra.css" "overflow-x: auto"
require_contains "${docs_root}/stylesheets/extra.css" "min-width: 40rem"
for reader_job in \
  "Learn by doing" "Complete a task" "Look up facts" "Understand why"; do
  require_contains "${docs_root}/index.md" "$reader_job"
done
for page in "${docs_root}"/how-to/*.md; do
  require_contains "$page" "# How to "
done
require_contains "${docs_root}/reference/cli.md" "cli-rs/protocol/source/commands.json"
require_contains "${docs_root}/reference/cli.md" '`selected.ready`'
require_contains "${docs_root}/reference/cli.md" \
  '`kast agent graph --operation summary`'
require_contains "${docs_root}/reference/cli.md" \
  "Runtime status does not report graph coverage"
require_contains "${docs_root}/reference/codex-plugin.md" "### Intents"
require_contains "${docs_root}/reference/codex-plugin.md" "### Result status"
require_contains "${docs_root}/reference/codex-plugin.md" "### Bounds and resumption"
require_contains "${docs_root}/explanation/architecture.md" \
  '<kast-view view-id="system-landscape"'
require_contains "${docs_root}/explanation/compiler-evidence.md" \
  '<kast-view view-id="compiler-evidence"'
require_contains "${docs_root}/explanation/compiler-evidence.md" \
  "Kotlin Analysis API (AA)"
require_contains "${docs_root}/explanation/compiler-evidence.md" \
  "Front-end Intermediate Representation (FIR)"
require_contains "${docs_root}/explanation/repository-intelligence.md" \
  '<kast-view view-id="runtime-components"'
hidden_system_map="${docs_root}/internal/system-flow.md"
require_contains "$hidden_system_map" "type: Runtime Flow"
require_contains "$hidden_system_map" "# How Kast works"
require_contains "$hidden_system_map" "## Public API coverage"
require_contains "$hidden_system_map" "## End-to-end system flow"
require_contains "$hidden_system_map" '<kast-view'
for view in \
  system-landscape runtime-components macos-runtime compiler-read \
  compiler-evidence semantic-mutation; do
  require_contains "$hidden_system_map" "$view"
done
require_contains "$hidden_system_map" "## Durable system invariants"
require_contains "$hidden_system_map" '## Answering "How does this part work?"'
require_contains "$hidden_system_map" "Open Knowledge Format"
require_contains "$hidden_system_map" 'kast agent verify'
require_contains "$hidden_system_map" 'kast agent repository'
require_contains "$hidden_system_map" 'kast agent symbol'
require_contains "$hidden_system_map" 'io.github.amichne.kast.api.contract.backend.AnalysisBackend'
for command in help version context config setup ready start status stop demo rpc developer agent; do
  require_contains "$hidden_system_map" "\`kast ${command}\`"
done
for command in \
  lease verify workspace-files graph repository symbol references callers callees \
  implementations hierarchy impact diagnostics rename add-file add-declaration \
  add-implementation add-statement replace-declaration; do
  require_contains "$hidden_system_map" "\`kast agent ${command}\`"
done
for source in \
  cli-rs/src/interface/cli/root.rs \
  cli-rs/src/interface/entrypoint/dispatch.rs \
  cli-rs/src/operations/install/bundle_entrypoint.rs \
  cli-rs/src/execution/runtime/backend/workspace.rs \
  cli-rs/src/agent/core/dispatch/commands.rs \
  cli-rs/src/agent/core/request.rs \
  cli-rs/src/interface/codex/hook/runtime.rs \
  cli-rs/protocol/source/commands.json \
  analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/backend/AnalysisBackend.kt \
  analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcAnalysisDispatcher.kt \
  analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcMethodRouter.kt \
  backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt \
  backend-headless/src/main/kotlin/io/github/amichne/kast/headless/runtime/HeadlessRuntime.kt \
  index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt; do
  require_contains "$hidden_system_map" "path: ${source}"
done
for record in "${repo_root}"/.agents/adr/[0-9]*.md; do
  require_contains "$hidden_system_map" \
    "path: .agents/adr/$(basename "$record")"
done

require_not_contains "$docs_root" "codex plugin marketplace add"
require_not_contains "$docs_root" "Homebrew"
require_not_contains "$docs_root" "kast repair"
require_not_contains "$docs_root" "kast machine"
require_not_contains "$docs_root" "raw/semantic-graph"
require_not_contains "$docs_root" "kast ready --for kotlin"
require_not_contains "$docs_root" "semanticGraph.state"

python3 - "$docs_root" "${expected_pages[@]}" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
for relative in sys.argv[2:]:
    if relative == "index.md":
        continue
    path = root / relative
    text = path.read_text()
    if not text.startswith("---\n"):
        raise SystemExit(f"{relative}: missing frontmatter")
    try:
        frontmatter = text.split("---\n", 2)[1]
    except IndexError:
        raise SystemExit(f"{relative}: unterminated frontmatter")
    if not any(line.startswith("type:") and line.removeprefix("type:").strip() for line in frontmatter.splitlines()):
        raise SystemExit(f"{relative}: missing non-empty OKF type")
    if "code_sources:\n" not in frontmatter:
        raise SystemExit(f"{relative}: missing code_sources")
PY

printf '%s\n' "Docs content contract passed"
