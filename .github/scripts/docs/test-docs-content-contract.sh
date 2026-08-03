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

require_present() {
  [[ -f "$1" ]] || die "required documentation file is missing: $1"
}

require_not_contains() {
  ! grep -R -Fq --exclude-dir=internal --include='*.md' -- "$2" "$1" || die "found '$2' under $1"
}

require_not_contains_file() {
  ! grep -Fq -- "$2" "$1" || die "found '$2' in $1"
}

require_not_contains_any_docs() {
  ! grep -R -Fq --include='*.md' -- "$2" "$1" || die "found '$2' under $1"
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
require_contains "$readme" 'After installation, `kast` is the agent interface'
require_contains "$readme" "libexec/kastctl"
require_contains "$readme" "prior active release usable"
require_contains "$readme" "--harness none"
require_contains "${docs_root}/how-to/install-or-update.md" "$installer"
require_contains "${docs_root}/how-to/install-or-update.md" "./gradlew refreshDevelopmentMachine"
require_contains "${docs_root}/how-to/install-or-update.md" "current/bin/kast"
require_contains "${docs_root}/how-to/install-or-update.md" "current/libexec/kastctl"
require_contains "${docs_root}/how-to/install-or-update.md" "./install.sh --force"
require_contains "${docs_root}/how-to/install-or-update.md" "--harness codex"
require_contains "${docs_root}/how-to/install-or-update.md" "No remote marketplace checkout is required"
require_contains "${docs_root}/tutorials/first-compiler-backed-task.md" "GradleModelSettlementOutcome"
require_contains "${docs_root}/how-to/explore-kotlin-code.md" "complete reported coverage"
require_contains "${docs_root}/how-to/plan-safe-edits.md" "one exact compiler identity"
for command in up refresh files symbol graph check change apply; do
  require_contains "${docs_root}/reference/cli.md" "\`kast ${command}"
done
public_mutation_docs=(
  "${docs_root}/how-to/plan-safe-edits.md"
  "${docs_root}/reference/cli.md"
  "${repo_root}/cli-rs/resources/kast/SKILL.md"
)
for public_mutation_doc in "${public_mutation_docs[@]}"; do
  require_not_contains_file "$public_mutation_doc" "kastctl agent lease"
  require_not_contains_file "$public_mutation_doc" "--lease-id"
done
for public_mutation_command in \
  'kast change' 'kast apply <PLAN_ID>' 'kast recover <RECOVERY_ID>'; do
  require_contains "${repo_root}/cli-rs/resources/kast/SKILL.md" \
    "$public_mutation_command"
  require_contains "${docs_root}/how-to/plan-safe-edits.md" \
    "$public_mutation_command"
  require_contains "${docs_root}/reference/cli.md" \
    "$public_mutation_command"
done
require_contains "${docs_root}/reference/cli.md" '`kast` is the only public interface'
require_contains "${docs_root}/reference/cli.md" "compact TOON"
require_contains "${docs_root}/reference/cli.md" '`libexec/kastctl` multicall entrypoint'
require_contains "${docs_root}/reference/cli.md" '`UNKNOWN` graph boundary'
require_contains "${docs_root}/reference/codex-plugin.md" "Codex, Claude,"
require_contains "${docs_root}/reference/codex-plugin.md" "installer never"
require_contains "${docs_root}/reference/codex-plugin.md" '`kast@kast`'
require_contains "${docs_root}/reference/codex-plugin.md" "kast-codex-<tag>.tar"
require_contains "${docs_root}/reference/codex-plugin.md" \
  "CLI, provider plugin, and skill"
require_contains "${docs_root}/reference/codex-plugin.md" \
  "version or digest mismatch rejects harness activation"
require_contains "${docs_root}/reference/codex-plugin.md" \
  "Direct CLI use does not require agent harness resources"
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
require_contains "${docs_root}/reference/cli.md" \
  '`kast graph [summary]`'
require_contains "${docs_root}/reference/cli.md" \
  "Diagnostics do not block reference indexing"
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
  system-landscape runtime-components indexer-runtime compiler-read \
  compiler-evidence semantic-mutation; do
  require_contains "$hidden_system_map" "$view"
done
require_contains "$hidden_system_map" "## Durable system invariants"
require_contains "$hidden_system_map" '## Answering "How does this part work?"'
require_contains "$hidden_system_map" "Open Knowledge Format"
require_contains "$hidden_system_map" '`libexec/kastctl` preserves the full administrative CLI'
require_contains "$hidden_system_map" '`libexec/kastctl setup`'
require_contains "$hidden_system_map" 'byte-identical `bin/kast` and'
require_contains "$hidden_system_map" '`kast refresh external <FAILURE_ID>...`'
require_contains "$hidden_system_map" 'io.github.amichne.kast.api.contract.backend.AnalysisBackend'
for command in up refresh files symbol graph check change apply; do
  require_contains "$hidden_system_map" "\`kast ${command}\`"
done
for source in \
  cli-rs/src/main.rs \
  cli-rs/src/interface/cli/agent/agent_surface.rs \
  cli-rs/src/agent/adapter/mod.rs \
  cli-rs/src/interface/cli/root.rs \
  cli-rs/src/interface/entrypoint/dispatch.rs \
  cli-rs/src/operations/install/bundle_entrypoint.rs \
  cli-rs/src/operations/install/agent_resources.rs \
  cli-rs/resources/kast/codex/hooks.json \
  cli-rs/resources/kast/claude/hooks.json \
  cli-rs/resources/kast/copilot/hooks.json \
  cli-rs/src/execution/runtime/backend/workspace.rs \
  cli-rs/src/execution/runtime/backend/workspace_admission.rs \
  cli-rs/src/agent/core/dispatch/commands.rs \
  cli-rs/src/agent/core/request.rs \
  cli-rs/protocol/source/commands.json \
  analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/backend/AnalysisBackend.kt \
  analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcAnalysisDispatcher.kt \
  analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcMethodRouter.kt \
  indexer/src/main/kotlin/io/github/amichne/kast/idea/backend/KastIndexerBackend.kt \
  indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerRuntime.kt \
  index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt; do
  require_contains "$hidden_system_map" "path: ${source}"
done
require_absent "${repo_root}/.agents/adr/0032-macos-idea-golden-pathway.md"
require_absent "${docs_root}/internal/idea-integration"
require_present "${docs_root}/internal/indexer/index.md"
for record in "${repo_root}"/.agents/adr/[0-9]*.md; do
  require_contains "$hidden_system_map" \
    "path: .agents/adr/$(basename "$record")"
done

require_not_contains "$docs_root" "codex plugin marketplace add"
require_not_contains "$docs_root" "amichne/kast-marketplace"
require_not_contains "$docs_root" "kagent"
require_not_contains "$docs_root" "Homebrew"
require_not_contains "$docs_root" "kast repair"
require_not_contains "$docs_root" "kast machine"
require_not_contains "$docs_root" "raw/semantic-graph"
require_not_contains "$docs_root" "kast ready --for kotlin"
require_not_contains "$docs_root" "semanticGraph.state"
retired_selector='--back''end idea'
retired_build_task='buildIdea''Plugin'
for retired_idea_surface in \
  "IDEA plugin" "$retired_selector" "$retired_build_task" "background-open"; do
  require_not_contains "$docs_root" "$retired_idea_surface"
  require_not_contains_file "$readme" "$retired_idea_surface"
done
for retired_public_command in \
  "kast agent" "kast developer" "kast setup" "kast status" "kast start" \
  "kast ready" "kast rpc" "kast demo"; do
  require_not_contains_any_docs "$docs_root" "$retired_public_command"
  require_not_contains_file "$readme" "$retired_public_command"
done
for retired_resource in \
  "amichne/kast-marketplace" "codex plugin marketplace add" "kagent"; do
  require_not_contains_file "$readme" "$retired_resource"
done

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
