#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
docs_root="${repo_root}/docs"
public_root="${docs_root}/public"
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
  ! grep -R -Fq --include='*.md' -- "$2" "$1" || die "found '$2' under $1"
}

require_not_contains_file() {
  ! grep -Fq -- "$2" "$1" || die "found '$2' in $1"
}

[[ -d "$public_root" ]] || die "public documentation root is missing: $public_root"

expected_pages=(
  "concepts/evidence-boundaries.md"
  "index.md"
  "questions/contract-change.md"
  "questions/dependents.md"
  "questions/resolve-declaration.md"
  "questions/value-flow.md"
  "questions/verify-coverage.md"
  "reference/semantic-operations.md"
)
actual_pages="$(find "$public_root" -type f -name '*.md' -print | sed "s#${public_root}/##" | sort)"
expected_page_lines="$(printf '%s\n' "${expected_pages[@]}" | sort)"
[[ "$actual_pages" == "$expected_page_lines" ]] || {
  printf 'expected pages:\n%s\nactual pages:\n%s\n' "$expected_page_lines" "$actual_pages" >&2
  die "public Markdown set differs from the problem-led documentation surface"
}

for obsolete in \
  index.md tutorials how-to reference stylesheets questions concepts; do
  require_absent "${docs_root}/${obsolete}"
done
require_absent "${docs_root}/explanation/architecture.md"
require_absent "${docs_root}/explanation/repository-intelligence.md"
compiler_evidence_compat="${docs_root}/explanation/compiler-evidence.md"
[[ -L "$compiler_evidence_compat" ]] || die "compiler-evidence compatibility path is not a symlink"
[[ "$(readlink "$compiler_evidence_compat")" == "../public/concepts/evidence-boundaries.md" ]] || \
  die "compiler-evidence compatibility path targets the wrong document"

home="${public_root}/index.md"
for question in \
  "What declaration does this actually refer to?" \
  "What depends on this API?" \
  "Where can this value flow?" \
  "What must change if this contract changes?" \
  "Did this change reach every semantic dependency?"; do
  require_contains "$home" "$question"
done
require_contains "$home" "Text can suggest"
require_contains "$home" "Compiler evidence can establish"

require_contains "${public_root}/questions/resolve-declaration.md" "exact compiler identity"
require_contains "${public_root}/questions/resolve-declaration.md" "ambiguous"
require_contains "${public_root}/questions/dependents.md" "relationship coverage"
require_contains "${public_root}/questions/value-flow.md" "does not prove runtime value flow"
require_contains "${public_root}/questions/contract-change.md" "bounded impact"
require_contains "${public_root}/questions/verify-coverage.md" "complete eligible coverage"

boundaries="${public_root}/concepts/evidence-boundaries.md"
for boundary in "complete evidence" "qualified evidence" "rejected request"; do
  require_contains "$boundaries" "$boundary"
done

generated_reference="${public_root}/reference/semantic-operations.md"
require_contains "$generated_reference" "> Generated file. Do not edit this page directly."
require_contains "$generated_reference" "cli-rs/protocol/source/commands.json"
require_contains "$generated_reference" "Response type"
"${repo_root}/.github/scripts/docs/generate-cli-reference.py" --check

for generic_agent_instruction in \
  "Ask your agent" "Start a Codex task" "Use this prompt" "Prompt:"; do
  require_not_contains "$public_root" "$generic_agent_instruction"
done

installer='/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"'
require_contains "$readme" "$installer"
require_contains "$readme" 'After installation, `kast` is the agent interface'
require_contains "$readme" "libexec/kastctl"
require_contains "$readme" "prior active release usable"
require_contains "$readme" "--harness none"
require_contains "${repo_root}/requirements-docs.txt" "zensical==0.0.51"
require_contains "${repo_root}/zensical.toml" 'docs_dir = "docs/public"'
require_contains "${repo_root}/zensical.toml" 'extra_css = ["stylesheets/extra.css"]'
require_contains "${repo_root}/zensical.toml" "[project.validation]"
require_contains "${repo_root}/zensical.toml" "invalid_links = true"
require_contains "${repo_root}/zensical.toml" "invalid_link_anchors = true"
require_contains "${public_root}/stylesheets/extra.css" ".md-typeset__table"
require_contains "${public_root}/stylesheets/extra.css" "overflow-x: auto"
require_contains "${public_root}/stylesheets/extra.css" ".evidence-contrast"

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
require_contains "$hidden_system_map" '`kast workspace externalize --failure-id <FAILURE_ID>`'
require_contains "$hidden_system_map" 'io.github.amichne.kast.api.contract.backend.AnalysisBackend'
for command in workspace file symbol relation graph diagnostic change; do
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
  require_contains "$hidden_system_map" "path: .agents/adr/$(basename "$record")"
done

for retired_public_term in \
  "codex plugin marketplace add" "amichne/kast-marketplace" "kagent" \
  "Homebrew" "kast repair" "kast machine" "raw/semantic-graph" \
  "kast ready --for kotlin" "semanticGraph.state"; do
  require_not_contains "$public_root" "$retired_public_term"
done

python3 - "$public_root" "${expected_pages[@]}" <<'PY'
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
    if not any(
        line.startswith("type:") and line.removeprefix("type:").strip()
        for line in frontmatter.splitlines()
    ):
        raise SystemExit(f"{relative}: missing non-empty OKF type")
    if "code_sources:\n" not in frontmatter:
        raise SystemExit(f"{relative}: missing code_sources")
PY

printf '%s\n' "Docs content contract passed"
