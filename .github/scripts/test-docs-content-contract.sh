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

require_file_not_contains() {
  local file_path="$1"
  local unexpected="$2"
  local description="$3"

  ! grep -Fq -- "$unexpected" "$file_path" \
    || die "${description}: found '${unexpected}' in ${file_path}"
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
macos_install_doc="${docs_root}/install/macos.md"
headless_doc="${docs_root}/install/headless-linux.md"
first_workflow_doc="${docs_root}/learn/first-semantic-workflow.md"
demo_doc="${docs_root}/learn/repository-demo.md"
evidence_doc="${docs_root}/learn/evidence-model.md"
choose_doc="${docs_root}/use/choose-a-command.md"
inspect_doc="${docs_root}/use/inspect-kotlin.md"
safe_edits_doc="${docs_root}/use/plan-safe-edits.md"
automate_doc="${docs_root}/use/automate-with-agents.md"
commands_ref="${docs_root}/reference/commands.md"
agent_ref="${docs_root}/reference/agent-commands.md"
selectors_ref="${docs_root}/reference/mutation-selectors.md"
runtime_ref="${docs_root}/reference/runtime-and-output.md"
troubleshoot_doc="${docs_root}/troubleshoot.md"
release_doc="${docs_root}/distribute/release-and-mirror.md"
artifact_doc="${docs_root}/distribute/runtime-artifact-contract.md"
local_development_doc="${docs_root}/distribute/local-development-refresh.md"
runtime_schema="${docs_root}/distribute/kast-runtime-manifest.schema.json"
operating_model_doc="${docs_root}/design/operating-model.md"
journey_map="${repo_root}/.agents/docs/documentation-journeys.md"
docs_adr="${repo_root}/.agents/adr/0011-journey-first-documentation-operating-model.md"
protocol_dir="${repo_root}/cli-rs/protocol"
api_specification="${protocol_dir}/api-specification.md"

[[ ! -e "${repo_root}/docs/docs.json" ]] || die "docs/docs.json must not be used; zensical.toml owns published navigation"
[[ ! -d "${docs_root}/adr" ]] || die "agent-focused ADRs must live under .agents/adr, not docs/adr"
if find "${docs_root}" -name AGENTS.md -print -quit | grep -q .; then
  die "AGENTS.md files are agent-only and must not live under published docs trees"
fi

require_absent_path "${repo_root}/cli-rs/docs" "The separate cli-rs docs site must not exist"
require_absent_path "${repo_root}/cli-rs/site" "The generated cli-rs docs site output must not exist"
require_absent_path "${repo_root}/cli-rs/zensical.toml" "The separate cli-rs Zensical site must not exist"
require_absent_path "${repo_root}/cli-rs/requirements-docs.txt" "The separate cli-rs docs toolchain must not exist"
require_absent_path "${docs_root}/getting-started" "Old getting-started docs must not remain published"
require_absent_path "${docs_root}/commands" "Old command-manual docs must not remain published"
require_absent_path "${docs_root}/distribution" "Old distribution docs must not remain published"
require_absent_path "${docs_root}/recipes.md" "Old recipe page must be replaced by journey pages"
require_absent_path "${docs_root}/troubleshooting.md" "Old troubleshooting path must be replaced by docs/troubleshoot.md"
require_absent_path "${docs_root}/superpowers" "Agent-only plans and specs must not live under published docs"
require_absent_path "${docs_root}/reference/api-reference.md" "Protocol/API reference markdown must not be published from docs/reference"
require_absent_path "${docs_root}/reference/api-specification.md" "Protocol/API specification markdown must not be published from docs/reference"
require_absent_path "${docs_root}/examples" "Protocol examples must not be published from docs/"
require_absent_path "${docs_root}/for-agents" "Agent essays must not be published from docs/"
require_absent_path "${docs_root}/architecture" "Architecture essays must not be published from docs/"
require_absent_path "${docs_root}/what-can-kast-do" "Use-case essays must not be published from docs/"
require_absent_path "${docs_root}/supported-use-cases.md" "Standalone use-case page must not be published"
require_absent_path "${docs_root}/cli-cheat-sheet.md" "Old CLI cheat sheet must be replaced by reference/commands.md"
require_absent_path "${docs_root}/getting-started/backends.md" "Backend essay must not be published"
[[ -f "${protocol_dir}/openapi.yaml" ]] || die "OpenAPI must remain generated outside the published docs tree"
[[ -f "${protocol_dir}/api-reference.md" ]] || die "Generated protocol markdown must remain outside the published docs tree"
require_contains "$api_specification" 'Result variants: `RESOLVE_SUCCESS`, `RESOLVE_NOT_FOUND`, `RESOLVE_AMBIGUOUS`, `RESOLVE_FAILURE`.' "Generated RPC summary must preserve every declared exact-resolve outcome"

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
require_not_contains "$docs_root" 'developer inspect demo' "Published docs must not advertise the retired developer demo"
require_not_contains "$docs_root" 'toon' "Published docs must not expose internal compact output modes"
require_not_contains "$docs_root" 'TOON' "Published docs must not expose internal compact output modes"
require_not_contains "$docs_root" 'curl --fail --location --remote-name' "Docs must not advertise the pre-brew-style installer download"
require_not_contains "$docs_root" 'chmod +x install.sh' "Docs must not require saving the root installer before first run"
require_file_not_contains "$troubleshoot_doc" 'developer runtime refresh' "Troubleshooting must not document removed runtime refresh command"
require_file_not_contains "$readme" 'cd /path/to/your/repository' "README install must not require directory-specific context"
require_file_not_contains "$readme" '--workspace-root "$PWD"' "README install must not expose workspace-root commands"
require_file_not_contains "$macos_install_doc" 'cd /path/to/your/repository' "macOS install must not require directory-specific context"
require_file_not_contains "$macos_install_doc" '--workspace-root "$PWD"' "macOS install must not expose workspace-root commands"

require_contains "$readme" "amichne/kast" "README must document the default Homebrew tap"
require_contains "$readme" '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"' "README must document the brew-style root macOS installer"
require_contains "$readme" "open your project in IntelliJ IDEA or Android Studio" "README must keep macOS setup developer-facing"
require_contains "$readme" "Agents use that backend behind the scenes" "README must describe agent execution as hidden behavior"
require_contains "$readme" "https://kast.michne.com/install/macos/" "README must route readers to the macOS install guide"
require_contains "$readme" "https://kast.michne.com/install/headless-linux/" "README must route readers to the headless Linux guide"
require_contains "$readme" "https://kast.michne.com/learn/first-semantic-workflow/" "README must route readers to the first semantic workflow"
require_contains "$readme" "https://kast.michne.com/learn/repository-demo/" "README must route readers to the repository demo"
require_contains "$readme" "https://kast.michne.com/reference/commands/" "README must route readers to command reference"
require_contains "$readme" "https://kast.michne.com/use/inspect-kotlin/" "README must route readers to inspection workflows"
require_contains "$readme" "scripts/install-ubuntu-debian.sh" "README must point non-Brew users to the canonical Ubuntu/Debian installer"
require_contains "$readme" 'kast demo' "README must expose the repository-native demo"

require_contains "$index_doc" "Start By Reader Job" "Landing page must route readers by journey"
require_contains "$index_doc" "Install on macOS" "Landing page must expose macOS install path"
require_contains "$index_doc" "Install on Linux" "Landing page must expose Linux install path"
require_contains "$index_doc" "First semantic workflow" "Landing page must route learners to the first semantic workflow"
require_contains "$index_doc" "Operating Model" "Landing page must explain the operating layers"
require_contains "$index_doc" "Command surface" "Landing page must link command reference"
require_contains "$index_doc" "Mutation selectors" "Landing page must link selector reference"
require_contains "$index_doc" "troubleshooting matrix" "Landing page must link troubleshooting"
require_contains "$index_doc" '??? info "Agent checks"' "Landing page must collapse agent checks"

require_contains "$macos_install_doc" "Use this path when you work on a local macOS project" "macOS install guide must state the reader job"
require_contains "$macos_install_doc" '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"' "macOS install guide must make the brew-style root installer primary"
require_contains "$macos_install_doc" "Normal developer use does not require running readiness, repair, or setup commands by hand" "macOS install guide must keep agent checks hidden"
require_contains "$macos_install_doc" "??? question \"What the IDE and agents handle\"" "macOS install guide must collapse agent setup detail"
require_contains "$macos_install_doc" "??? info \"Advanced installer controls\"" "macOS install guide must collapse advanced installer commands"
require_contains "$macos_install_doc" "workspace setup is owned by the IntelliJ plugin" "macOS install guide must make plugin-owned setup primary"
require_contains "$macos_install_doc" "Homebrew owns the Kast CLI and JetBrains owns the signed plugin" "macOS install guide must separate distribution authorities"
require_contains "$macos_install_doc" "JetBrains owns subsequent plugin updates" "macOS install guide must keep plugin updates JetBrains-owned"
require_contains "$macos_install_doc" "not inspect, close, or mutate an IDE" "macOS install guide must reject IDE mutation by the installer"
require_contains "$macos_install_doc" "only when repair has proved that an owned legacy symlink" "macOS install guide must bound legacy editor closure"
require_not_contains "$macos_install_doc" 'Enter `y` to close the detected editor and continue' "macOS install guide must retire interactive editor closure"
require_not_contains "$macos_install_doc" 'kill -TERM <pid>' "macOS install guide must retire installer-owned editor termination"

require_contains "$headless_doc" "Use this path for CI runners, hosted agents, server images" "Headless install guide must state the reader job"
require_contains "$headless_doc" 'scripts/install-ubuntu-debian.sh' "Headless docs must document the canonical Linux installer"
require_contains "$headless_doc" '??? info "Agent bootstrap details"' "Headless docs must collapse repository guidance details"
require_contains "$headless_doc" '??? info "Backend checks"' "Headless docs must collapse backend checks"
require_contains "$headless_doc" 'KAST_UBUNTU_DEBIAN_ARTIFACT_PATH' "Headless docs must document local artifact installs"
require_contains "$headless_doc" 'runtime-artifact-contract.md' "Headless docs must link the runtime artifact contract"

require_contains "$first_workflow_doc" "not a copy-paste tutorial for developers" "First workflow must not present agent commands as developer steps"
require_contains "$first_workflow_doc" "??? info \"Agent execution details\"" "First workflow must collapse command execution detail"
require_contains "$first_workflow_doc" "Mutation commands also plan first" "First workflow must cover plan-first mutation"

require_contains "$demo_doc" 'kast demo --workspace-root "$PWD"' "Demo docs must show the root command"
require_contains "$demo_doc" "read-only" "Demo docs must state the mutation boundary"
require_contains "$demo_doc" '--symbol' "Demo docs must cover explicit symbol selection"
require_contains "$demo_doc" '--output json' "Demo docs must cover structured output"
require_contains "$demo_doc" '`backendOnly`' "Demo docs must explain backend-only degradation"

require_contains "$evidence_doc" "Identity Comes Before Text" "Evidence explanation must explain identity"
require_contains "$evidence_doc" "Evidence Can Be Bounded" "Evidence explanation must explain bounded results"
require_contains "$evidence_doc" "Plans Carry Write Evidence" "Evidence explanation must explain plan-first edits"
require_contains "$evidence_doc" "Layers Stay Separate" "Evidence explanation must explain operating layers"

require_contains "$choose_doc" "Start With The Job" "Command chooser must be task-oriented"
require_contains "$choose_doc" "Agent and operator command families" "Command chooser must collapse exact command families"
require_contains "$choose_doc" "Prefer typed agent commands over raw transport" "Command chooser must reject raw public workflow"
require_contains "$inspect_doc" "Resolve Identity First" "Inspection guide must cover symbol identity"
require_contains "$inspect_doc" "Agent inspection commands" "Inspection guide must collapse exact commands"
require_contains "$safe_edits_doc" "Every public mutation path is plan-first" "Safe edits guide must preserve plan-first behavior"
require_contains "$safe_edits_doc" "Local-variable rename is not part of the current public dialect" "Safe edits guide must document local rename boundary"
require_contains "$safe_edits_doc" "??? info \"Mutation command examples\"" "Safe edits guide must collapse mutation commands"
require_contains "$automate_doc" "Keep automation on the public command dialect" "Agent automation guide must reject raw workflow"
require_contains "$automate_doc" "Prefer Readable Or JSON Output" "Agent automation guide must keep public output modes simple"
require_contains "$automate_doc" "??? info \"Agent bootstrap commands\"" "Agent automation guide must collapse bootstrap commands"

require_contains "$commands_ref" "Root Commands" "Command reference must document root commands"
require_contains "$commands_ref" '`kast demo`' "Command reference must document the public repository demo"
require_contains "$commands_ref" "Public Command Groups" "Command reference must document public groups"
require_contains "$commands_ref" "Machine" "Command reference must list machine commands"
require_contains "$commands_ref" "Release" "Command reference must list release commands"
require_contains "$commands_ref" "Setup Boundary" "Command reference must document setup boundary"
require_contains "$commands_ref" "Human-facing output is readable" "Command reference must only expose readable and JSON output"
require_contains "$commands_ref" "Workspace and backend examples" "Command reference must collapse workspace examples"
require_contains "$agent_ref" "What Agents Ask For" "Agent reference must describe capabilities before commands"
require_contains "$agent_ref" "??? info \"Command names for agent authors\"" "Agent reference must collapse command names"
require_contains "$agent_ref" "??? info \"Example agent execution\"" "Agent reference must collapse examples"
require_contains "$selectors_ref" "Selector Concepts" "Selector reference must explain concepts first"
require_contains "$selectors_ref" "??? info \"Selector flags for agent authors\"" "Selector reference must collapse selector flags"
require_contains "$selectors_ref" "??? info \"Placement anchors\"" "Selector reference must collapse anchors"
require_contains "$selectors_ref" "Local-variable rename is not part of the current public dialect" "Selector reference must document local rename boundary"
require_contains "$runtime_ref" "Output Shapes" "Runtime reference must document public output shapes"
require_contains "$runtime_ref" "Human-facing commands should be readable" "Runtime reference must keep output modes simple"
require_contains "$runtime_ref" "Runtime commands for agents and support" "Runtime reference must collapse runtime commands"

require_contains "$troubleshoot_doc" "Diagnostic Matrix" "Troubleshooting must use a diagnostic matrix"
require_contains "$troubleshoot_doc" "Read-only checks for agents and support" "Troubleshooting must collapse read-only checks"
require_contains "$troubleshoot_doc" "Keep Fixes Plan-First" "Troubleshooting must preserve plan-first repair guidance"

require_contains "$release_doc" "kast developer release package ubuntu-debian-bundle" "Release guide must cover bundle packaging"
require_contains "$release_doc" "scripts/verify-release-assets.sh" "Release guide must cover release verification"
require_contains "$release_doc" "kast developer release activate bundle" "Release guide must cover bundle activation"
require_contains "$release_doc" "KAST_UBUNTU_DEBIAN_BASE_URL" "Release guide must cover mirrored release directories"
require_contains "$artifact_doc" "kast-ubuntu-debian-headless-x86_64-<version>.tar.gz" "Artifact contract must define bundle name"
require_contains "$artifact_doc" "kast-runtime-manifest.schema.json" "Artifact contract must link runtime schema"
require_contains "$artifact_doc" "artifactSha256" "Artifact contract must document artifact digest"
require_contains "$artifact_doc" "scripts/verify-ci-artifact-ledger.py verify" "Artifact contract must document build ledger verification"
require_contains "$artifact_doc" 'kast-action@v2' "Artifact contract must document hosted-agent compatibility"
require_contains "$artifact_doc" "kast-local-prepared-generation.tar.zst" "Artifact contract must define the immutable prepared-generation archive"
require_contains "$artifact_doc" "one producer" "Artifact contract must state single-producer ownership"
require_contains "$local_development_doc" "prepareDevelopmentLocalGeneration" "Local development docs must expose build-once generation preparation"
require_contains "$local_development_doc" "activateDevelopmentLocal" "Local development docs must expose rebuild-free generation activation"
require_contains "$local_development_doc" "generation.json" "Local development docs must identify the strict prepared-generation ledger"
require_contains "$local_development_doc" "two-module Gradle fixture" "Local development docs must distinguish the required representative semantic proof from the full canary"
require_contains "$runtime_schema" '"$id": "https://kast.michne.com/distribute/kast-runtime-manifest.schema.json"' "Runtime schema id must match new public path"

require_contains "$operating_model_doc" "The Layer Boundary" "Operating model must explain layers"
require_contains "$operating_model_doc" "Why Setup Differs By Host" "Operating model must explain host setup split"
require_contains "$operating_model_doc" "Why Commands Stay Typed" "Operating model must explain typed commands"
require_contains "$operating_model_doc" "Why Plans Precede Writes" "Operating model must explain plan-first mutations"

require_contains "$journey_map" "Diataxis Page Map" "Agent journey map must define page roles"
require_contains "$journey_map" "docs/reference/commands.md" "Agent journey map must include command reference"
require_contains "$journey_map" "docs/distribute/runtime-artifact-contract.md" "Agent journey map must include distribution reference"
require_contains "$docs_adr" "Journey-first documentation operating model" "ADR must record the new docs operating model"
require_contains "$docs_adr" "This ADR supersedes ADR 0003" "ADR must supersede the old command-manual model"
require_contains "$docs_adr" "Public Navigation" "ADR must record public navigation"

require_embedded_markdown_links

printf '%s\n' "Docs content contract passed"
