#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"

  grep -Fq -- "$expected" "$file_path" || die "${description}: missing '${expected}' in ${file_path}"
}

require_not_contains() {
  local root_path="$1"
  local unexpected="$2"
  local description="$3"

  ! grep -R -Fq --include='*.md' -- "$unexpected" "$root_path" \
    || die "${description}: found '${unexpected}' under ${root_path}"
}

require_absent_path() {
  local path="$1"
  local description="$2"

  [[ ! -e "$path" ]] || die "$description: ${path} exists"
}

require_embedded_markdown_links() {
  local failed="false"
  local file_path

  while IFS= read -r file_path; do
    awk '
      /^```/ { in_fence = !in_fence; next }
      in_fence { next }
      /<https?:\/\/[^>]+>/ {
        printf "%s:%d: use descriptive markdown link text instead of angle autolink\n", FILENAME, FNR
        failed = 1
      }
      /\[[^]]*(https?:\/\/|www\.|[[:alnum:]_.-]+\.[[:alnum:]_.-]+\/)[^]]*\]\(/ {
        printf "%s:%d: link text should describe the destination, not repeat the URL\n", FILENAME, FNR
        failed = 1
      }
      END { exit failed }
    ' "$file_path" || failed="true"
  done < <(
    {
      printf '%s\n' "$readme"
      find "$docs_root" -type f -name '*.md'
    } | sort
  )

  [[ "$failed" != "true" ]] || die "Docs and README links must be embedded in descriptive text"
}

repo_root="$(resolve_repo_root)"
docs_root="${repo_root}/docs"
readme="${repo_root}/README.md"
index_doc="${docs_root}/index.md"
install_doc="${docs_root}/getting-started/install.md"
headless_doc="${docs_root}/getting-started/headless-linux.md"
quickstart_doc="${docs_root}/getting-started/quickstart.md"
commands_index="${docs_root}/commands/index.md"
lifecycle_doc="${docs_root}/commands/lifecycle.md"
install_repair_doc="${docs_root}/commands/install-repair.md"
agent_doc="${docs_root}/commands/agent.md"
metrics_doc="${docs_root}/commands/metrics.md"
lsp_doc="${docs_root}/commands/lsp.md"
recipes_doc="${docs_root}/recipes.md"
troubleshooting_doc="${docs_root}/troubleshooting.md"
distribution_doc="${docs_root}/distribution/runtime-artifact-contract.md"
docs_adr="${repo_root}/.agents/adr/0003-cli-command-documentation-operating-model.md"
protocol_dir="${repo_root}/cli-rs/protocol"

[[ ! -e "${repo_root}/docs/docs.json" ]] || die "docs/docs.json must not be used; zensical.toml owns published navigation"
[[ ! -d "${docs_root}/adr" ]] || die "agent-focused ADRs must live under .agents/adr, not docs/adr"
if find "${docs_root}" -name AGENTS.md -print -quit | grep -q .; then
  die "AGENTS.md files are agent-only and must not live under published docs trees"
fi

require_absent_path "${repo_root}/cli-rs/docs" "The separate cli-rs docs site must not exist"
require_absent_path "${repo_root}/cli-rs/site" "The generated cli-rs docs site output must not exist"
require_absent_path "${repo_root}/cli-rs/zensical.toml" "The separate cli-rs Zensical site must not exist"
require_absent_path "${repo_root}/cli-rs/requirements-docs.txt" "The separate cli-rs docs toolchain must not exist"
require_absent_path "${docs_root}/reference" "Protocol/API references must not be published from docs/"
require_absent_path "${docs_root}/examples" "Protocol examples must not be published from docs/"
require_absent_path "${docs_root}/for-agents" "Agent essays must not be published from docs/"
require_absent_path "${docs_root}/architecture" "Architecture essays must not be published from docs/"
require_absent_path "${docs_root}/what-can-kast-do" "Use-case essays must not be published from docs/"
require_absent_path "${docs_root}/supported-use-cases.md" "Standalone use-case page must not be published"
require_absent_path "${docs_root}/cli-cheat-sheet.md" "Old CLI cheat sheet must be replaced by commands/"
require_absent_path "${docs_root}/getting-started/backends.md" "Backend essay must not be published"
[[ -f "${protocol_dir}/openapi.yaml" ]] || die "OpenAPI must remain generated outside the published docs tree"
[[ -f "${protocol_dir}/api-reference.md" ]] || die "Generated protocol markdown must remain outside the published docs tree"

require_not_contains "$docs_root" "reference/api-reference" "Published docs must not link generated API reference"
require_not_contains "$docs_root" "reference/api-specification" "Published docs must not link API specification"
require_not_contains "$docs_root" "openapi.yaml" "Published docs must not link OpenAPI YAML"
require_not_contains "$docs_root" "for-agents/" "Published docs must not link deleted agent pages"
require_not_contains "$docs_root" "what-can-kast-do/" "Published docs must not link deleted use-case pages"
require_not_contains "$docs_root" "architecture/" "Published docs must not link deleted architecture pages"
require_not_contains "$docs_root" "supported-use-cases" "Published docs must not link deleted use-case page"
require_not_contains "$docs_root" "cli-cheat-sheet" "Published docs must not link the deleted cheat sheet"
require_not_contains "$docs_root" '$(pwd)' "Docs must not rely on unquoted workspace-root command substitution"
require_not_contains "$docs_root" 'doctor' "Docs must not advertise the retired doctor command vocabulary"
require_not_contains "$docs_root" 'amichne/kast-action@v1' "Docs must not document the retired hosted-agent installer"
require_not_contains "$docs_root" 'repository-backed' "Docs must use concrete install-scope wording"
require_not_contains "$docs_root" 'kast rpc' "Docs must not document the removed RPC shell flow"
require_not_contains "$docs_root" 'kast runtime ' "Docs must not document retired top-level runtime aliases"
require_not_contains "$docs_root" 'kast inspect ' "Docs must not document retired top-level inspect aliases"
require_not_contains "$docs_root" 'kast machine ' "Docs must not document retired top-level machine aliases"
require_not_contains "$docs_root" 'kast release ' "Docs must not document retired top-level release aliases"
require_not_contains "$docs_root" 'kast agent raw-' "Docs must not document retired agent raw aliases"

require_contains "$readme" "brew tap amichne/kast" "README must document the Homebrew tap install"
require_contains "$readme" "brew install kast" "README must document the global Homebrew binary install"
require_contains "$readme" "matching IntelliJ plugin" "README must document the Homebrew-managed IDEA plugin artifact"
require_contains "$readme" "kast developer machine plugin" "README must document JetBrains profile repair"
require_contains "$readme" "skill-only, runtime-only, or resource-only" "README must reject partial macOS setup"
require_contains "$readme" "command manual" "README must route readers to command documentation"
require_contains "$readme" "scripts/install-ubuntu-debian.sh" "README must point non-Brew users to the canonical Ubuntu/Debian installer"

require_contains "$index_doc" "Developer-oriented command documentation" "Overview must be command-manual oriented"
require_contains "$index_doc" "invocation metadata when the workspace opens" "Overview must show plugin-owned macOS setup"
require_contains "$index_doc" 'non-macOS repository resources | `kast setup ...`' "Overview must keep setup scoped to non-macOS repository resources"
require_contains "$index_doc" "Commands" "Overview must route to command docs"
require_contains "$index_doc" "typed Kast agent commands" "Overview must route automation through typed agent commands"

require_contains "$install_doc" "## Developer machine" "Install docs must distinguish the developer-machine path"
require_contains "$install_doc" "brew install kast" "Install docs must document the Homebrew developer distribution"
require_contains "$install_doc" "kast developer machine plugin" "Install docs must document JetBrains profile repair"
require_contains "$install_doc" "The plugin prepares the workspace" "Install docs must make plugin-owned setup primary"
require_contains "$install_doc" ".agents/skills/kast" "Install docs must document the shared skill target"
require_contains "$install_doc" ".kast/setup/workspace.json" "Install docs must document workspace metadata"
require_contains "$install_doc" '<kast>...</kast>' "Install docs must document managed agent guidance ownership"
require_contains "$install_doc" "The CLI does not install skill-only, runtime-only" "Install docs must reject partial macOS setup"
require_contains "$install_doc" "plugin backs them up and removes them" "Install docs must document intentional upgrade removal"
require_contains "$install_doc" "kast repair --for agent" "Install docs must document repair"
require_contains "$install_doc" "kast developer inspect paths" "Install docs must document path inspection"
require_contains "$install_doc" "headless-linux.md" "Install docs must link the separate headless server page"
require_contains "$headless_doc" 'scripts/install-ubuntu-debian.sh' "Headless docs must document the canonical Linux installer"
require_contains "$headless_doc" 'kast developer release package ubuntu-debian-bundle' "Headless docs must document the release bundle packager"

require_contains "$quickstart_doc" "Homebrew-installed Kast plugin enabled" "Quickstart must use plugin-owned macOS setup"
require_contains "$quickstart_doc" ".kast/setup/workspace.json" "Quickstart must document plugin workspace metadata"
require_contains "$quickstart_doc" "kast setup --workspace-root" "Quickstart must keep non-macOS setup guidance"
require_contains "$quickstart_doc" "kast agent symbol --query" "Quickstart must use typed symbol lookup"
require_contains "$quickstart_doc" "kast agent diagnostics" "Quickstart must use typed diagnostics"
require_contains "$quickstart_doc" "kast agent rename --symbol" "Quickstart must use identity-first rename"
require_not_contains "$quickstart_doc" "kast rpc" "Quickstart must not teach raw RPC"

require_contains "$commands_index" "Runtime" "Command overview must list runtime commands"
require_contains "$commands_index" "Machine" "Command overview must list machine commands"
require_contains "$commands_index" "Agent automation" "Command overview must list agent commands"
require_contains "$commands_index" "Non-macOS repository guidance setup; macOS workspace setup is owned by the IntelliJ plugin" "Command overview must scope setup by platform"
require_contains "$lifecycle_doc" "kast developer runtime up" "Lifecycle docs must cover up"
require_contains "$lifecycle_doc" "kast developer runtime status" "Lifecycle docs must cover status"
require_contains "$lifecycle_doc" "kast developer runtime restart" "Lifecycle docs must cover restart"
require_contains "$lifecycle_doc" "kast developer runtime stop" "Lifecycle docs must cover stop"
require_contains "$install_repair_doc" "kast setup" "Install command docs must cover harness-agnostic setup"
require_contains "$install_repair_doc" ".agents/skills/kast" "Install command docs must cover the shared skill target"
require_contains "$install_repair_doc" "AGENTS.local.md" "Install command docs must cover local agent guidance"
require_contains "$install_repair_doc" "--context-file" "Install command docs must cover explicit agent guidance targets"
require_contains "$install_repair_doc" "kast setup --dry-run" "Install command docs must cover setup planning"
require_contains "$install_repair_doc" "agentsMdTargets" "Install command docs must cover guidance target dry-run output"
require_contains "$install_repair_doc" "kast repair --workspace-root" "Install command docs must cover repair"
require_contains "$agent_doc" "kast agent symbol --query" "Agent command docs must cover typed symbol lookup"
require_contains "$agent_doc" "kast setup --dry-run" "Agent command docs must cover harness-agnostic setup planning"
require_contains "$agent_doc" ".agents/skills/kast" "Agent command docs must cover the shared skill target"
require_contains "$agent_doc" "AGENTS.local.md" "Agent command docs must cover local agent guidance"
require_contains "$agent_doc" "--context-file" "Agent command docs must cover explicit agent guidance targets"
require_contains "$agent_doc" "does not install Copilot package files" "Agent command docs must keep setup asset scope minimal"
require_contains "$agent_doc" "kast agent rename --symbol" "Agent docs must cover identity-first rename"
require_contains "$metrics_doc" "kast developer inspect metrics fan-in" "Metrics docs must cover direct metrics"
require_contains "$metrics_doc" "kast agent impact --symbol" "Metrics docs must cover typed agent impact"
require_contains "$lsp_doc" "kast agent lsp --stdio" "LSP docs must cover the stdio command"
require_contains "$recipes_doc" "kast agent rename --symbol" "Recipes must cover safe rename planning"
require_contains "$recipes_doc" "kast agent diagnostics" "Recipes must cover diagnostics"
require_contains "$troubleshooting_doc" "kast --output json ready" "Troubleshooting must start from readiness"
require_contains "$troubleshooting_doc" "kast --output json agent verify" "Troubleshooting must include agent health"
require_contains "$distribution_doc" "kast developer release package ubuntu-debian-bundle" "Distribution docs must cover bundle packaging"
require_contains "$distribution_doc" "kast developer release activate bundle" "Distribution docs must cover bundle activation"
require_contains "$distribution_doc" "scripts/verify-release-assets.sh" "Distribution docs must cover release verification"

require_contains "$docs_adr" "CLI command documentation operating model" "ADR must record the new docs operating model"
require_contains "$docs_adr" "Protocol artifacts" "ADR must keep protocol artifacts outside published docs"

require_embedded_markdown_links

printf '%s\n' "Docs content contract passed"
