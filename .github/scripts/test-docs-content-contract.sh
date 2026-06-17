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

line_number_for() {
  local file_path="$1"
  local needle="$2"

  grep -nF -- "$needle" "$file_path" | head -1 | cut -d: -f1
}

require_order() {
  local file_path="$1"
  local earlier="$2"
  local later="$3"
  local description="$4"

  local earlier_line
  local later_line
  earlier_line="$(line_number_for "$file_path" "$earlier")"
  later_line="$(line_number_for "$file_path" "$later")"

  [[ -n "$earlier_line" ]] || die "${description}: missing earlier marker '${earlier}' in ${file_path}"
  [[ -n "$later_line" ]] || die "${description}: missing later marker '${later}' in ${file_path}"
  [[ "$earlier_line" -lt "$later_line" ]] || die "${description}: '${earlier}' must appear before '${later}' in ${file_path}"
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
      find "$docs_root" -type f -name '*.md' \
        ! -path "${docs_root}/reference/api-reference.md" \
        ! -path "${docs_root}/reference/capabilities.md"
    } | sort
  )

  [[ "$failed" != "true" ]] || die "Docs and README links must be embedded in descriptive text"
}

repo_root="$(resolve_repo_root)"
docs_root="${repo_root}/docs"
readme="${repo_root}/README.md"
install_doc="${docs_root}/getting-started/install.md"
backends_doc="${docs_root}/getting-started/backends.md"
quickstart_doc="${docs_root}/getting-started/quickstart.md"
agents_doc="${docs_root}/for-agents/index.md"
index_doc="${docs_root}/index.md"
agent_install_doc="${docs_root}/for-agents/install-the-skill.md"
use_cases_doc="${docs_root}/supported-use-cases.md"

require_not_contains "$docs_root" '$HOME/.kast/lib/backends' "Docs must use the installer-managed backend path"
require_not_contains "$docs_root" '$HOME/.kast/bin/kast' "Docs must not document the retired kast.sh binary path"
require_not_contains "$docs_root" ".kast/.manifest.json" "Docs must not document the retired kast.sh manifest path"
require_not_contains "$docs_root" '$(pwd)' "Docs must not rely on unquoted workspace-root command substitution"
require_not_contains "$docs_root" 'IntelliJ plugin-backed runtime' "Docs must use IDEA plugin naming for user-facing backend labels"
require_not_contains "$docs_root" 'Install the IntelliJ plugin' "Docs must use IDEA plugin naming for manual plugin install guidance"
require_not_contains "$docs_root" '--backend-name=intellij' "Docs must use idea as the IDE-hosted backend name"
require_not_contains "$docs_root" 'backends.intellij' "Docs must use idea as the IDE-hosted backend config section"
require_not_contains "$docs_root" 'kast-intellij' "Docs must use idea as the plugin release asset name"
require_not_contains "$docs_root" 'backend-intellij' "Docs must use idea as the IDE-hosted backend module name"
require_not_contains "$docs_root" 'kast install headless' "Docs must not document standalone headless backend installation"
require_not_contains "$docs_root" 'repository-backed' "Docs must use concrete install-scope wording"
require_not_contains "$docs_root" 'repository backed' "Docs must use concrete install-scope wording"
require_not_contains "$readme" '$(pwd)' "README must not rely on unquoted workspace-root command substitution"
require_not_contains "$readme" 'IntelliJ plugin-backed runtime' "README must use IDEA plugin naming for user-facing backend labels"
require_not_contains "$readme" 'IntelliJ-backed' "README must use IDEA-backed naming"
require_not_contains "$readme" 'backend-intellij' "README must use idea as the IDE-hosted backend module name"
require_not_contains "$readme" 'kast install headless' "README must not document standalone headless backend installation"

require_contains "$readme" "brew tap amichne/kast" "README must document the Homebrew tap install"
require_contains "$readme" "brew install kast" "README must document the global Homebrew binary install"
require_contains "$readme" "kast install copilot" "README must document repository-local Copilot installation"
require_contains "$readme" "Machine install" "README must distinguish machine install scope"
require_contains "$readme" "Repository install" "README must distinguish repository install scope"
require_contains "$readme" "Linux headless bundle" "README must document the Linux headless bundle distribution"
require_not_contains "$readme" "curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash" "README must not document the retired shell installer"
require_not_contains "$readme" "amichne/kast-action@v1" "README must not document a separate hosted-agent installer"
require_contains "$readme" "scripts/install-ubuntu-debian.sh" "README must point non-Brew users to the canonical Ubuntu/Debian installer"
require_contains "$index_doc" "scripts/install-ubuntu-debian.sh install" "Docs overview must show the Linux headless tarball install example"
require_contains "$index_doc" "install the `kast` binary once on the machine" "Docs overview must distinguish the global binary scope"
require_contains "$index_doc" "add Copilot integration" "Docs overview must distinguish the repository integration scope"
require_contains "$index_doc" "kast install copilot" "Docs overview must show the repository Copilot install command"

require_contains "$install_doc" "## Developer machine" "Install docs must distinguish the developer-machine path"
require_contains "$install_doc" "## Headless Linux server" "Install docs must document the Linux headless server path"
require_contains "$install_doc" "kast setup" "Install docs must document first-run setup"
require_contains "$install_doc" "brew install --cask kast-plugin" "Install docs must document Homebrew-managed IDEA plugin assets"
require_contains "$install_doc" "brew install kast" "Install docs must document the global Homebrew binary install"
require_contains "$install_doc" "Machine" "Install docs must label machine-level scope"
require_contains "$install_doc" "Repository" "Install docs must label repository-level scope"
require_not_contains "$install_doc" "--skip-headless" "Install docs must not describe setup as a headless deployment control"
require_not_contains "$install_doc" "## Shell installer" "Install docs must not document the retired shell installer"
require_not_contains "$install_doc" "## Install modes" "Install docs must not document retired kast.sh install modes"
require_not_contains "$install_doc" "amichne/kast-action@v1" "Install docs must not document a separate hosted-agent installer"
require_not_contains "$install_doc" "bundle-url" "Install docs must not document the retired action mirror input"
require_not_contains "$install_doc" "bundle-sha256" "Install docs must not document the retired action mirror checksum input"
require_not_contains "$install_doc" "skip-copilot-extension: true" "Install docs must not document retired Copilot-extension action options"
require_not_contains "$install_doc" "KAST_INSTALL_SOURCE=action" "Install docs must not document retired action install metadata"
require_not_contains "$install_doc" 'scripts/headless-agent-install.sh' "Install docs must not document the retired headless agent installer"
require_not_contains "$install_doc" 'scripts/package-headless-agent-bundle.sh' "Install docs must not document retired bundle packaging"
require_contains "$install_doc" '## Headless Linux server' "Install docs must document the Linux headless server path"
require_contains "$install_doc" 'scripts/install-ubuntu-debian.sh' "Install docs must document the canonical non-Brew installer"
require_contains "$install_doc" 'scripts/package-ubuntu-debian-bundle.sh' "Install docs must document the canonical Linux headless tarball packager"
require_contains "$install_doc" 'kast-ubuntu-debian-headless-x86_64-<version>.tar.gz' "Install docs must name the Linux headless tarball asset"
require_contains "$install_doc" 'scripts/verify-release-assets.sh' "Install docs must document release asset verification"
require_contains "$install_doc" 'Repository Copilot integration' "Install docs must document the repository Copilot package"
require_contains "$install_doc" 'kast install copilot' "Install docs must make the CLI Copilot install path primary"
require_contains "$install_doc" '.github/lsp.json' "Install docs must document the LSP config output"
require_contains "$install_doc" '.github/agents/kast-reader.agent.md' "Install docs must document the reader agent output"
require_contains "$install_doc" '.github/agents/kast-writer.agent.md' "Install docs must document the writer agent output"
require_contains "$install_doc" '.github/extensions/kast/_shared/commands.json' "Install docs must document the command catalog output"
require_contains "$install_doc" '.github/.kast-copilot-version' "Install docs must document the managed package version marker"
require_contains "$install_doc" '--plugin-dir cli-rs/resources/plugin' "Install docs must document the Copilot CLI source-plugin validation path"
require_not_contains "$install_doc" '## Install the legacy Copilot extension' "Install docs must not make the deprecated SDK extension an install path"
require_not_contains "$install_doc" 'KAST_AGENT_CLI_URL' "Install docs must not mention retired headless agent variables"
require_not_contains "$install_doc" 'KAST_AGENT_BACKEND_URL' "Install docs must not mention retired headless agent variables"
require_not_contains "$install_doc" 'KAST_SKIP_COPILOT_EXTENSION' "Install docs must not mention retired headless agent variables"
require_contains "$install_doc" 'KAST_UBUNTU_DEBIAN_ARTIFACT_PATH' "Install docs must list the canonical local artifact override"
require_contains "$install_doc" 'lib/backends/headless-<version>/runtime-libs' "Install docs must name the installed headless runtime-libs path"
require_not_contains "$install_doc" 'push the plugin archive directly' "Install docs must not document retired shell-installer plugin push behavior"

require_not_contains "$docs_root" '$HOME/.kast/backends/current/runtime-libs' "Docs must not document the retired kast.sh backend path"
require_contains "$backends_doc" '$HOME/.local/share/kast/ubuntu-debian/<version>/lib/backends/headless-<version>/runtime-libs' "Backend docs must use the Ubuntu/Debian installer-managed runtime-libs path"
require_contains "$backends_doc" 'IDEA / Android Studio plugin backend' "Backend docs must use IDEA plugin naming"
require_contains "$backends_doc" '`idea` backend name' "Backend docs must document idea as the stable IDE-hosted backend name"
require_contains "$quickstart_doc" 'APP_FILE="$PWD/src/main/kotlin/App.kt"' "Quickstart must show a shell-expanded absolute file path"
require_not_contains "$quickstart_doc" '--workspace-root="$PWD"' "Quickstart must rely on workspace-root autodetection"
require_contains "$quickstart_doc" 'scripts/install-ubuntu-debian.sh install' "Quickstart must show Linux headless tarball installation"
require_contains "$agents_doc" "## Agent setup in two scopes" "Agent docs must separate machine and repository setup"
require_contains "$agents_doc" "brew install kast" "Agent docs must show global binary setup"
require_contains "$agents_doc" "kast install copilot" "Agent docs must show repository Copilot setup"
require_contains "$agents_doc" "Ubuntu/Debian headless bundle" "Agent docs must point headless agents at the Linux bundle"
require_not_contains "$agents_doc" "amichne/kast-action@v1" "Agent docs must not document a separate hosted-agent installer"
require_not_contains "$agents_doc" 'scripts/headless-agent-install.sh' "Agent docs must not document the retired headless agent installer"
require_not_contains "$agents_doc" 'KAST_AGENT_INSTALL_ROOT' "Agent docs must not mention retired headless agent variables"
require_contains "$agent_install_doc" '## Install into this repository' "Agent install docs must make repository Copilot install primary"
require_contains "$agent_install_doc" '--plugin-dir cli-rs/resources/plugin' "Agent install docs must document the Copilot CLI source-plugin validation path"
require_contains "$use_cases_doc" 'Global binary' "Use case docs must reinforce the global binary scope"
require_contains "$use_cases_doc" 'Repository Copilot files' "Use case docs must reinforce the repository integration scope"
require_embedded_markdown_links
python3 "${repo_root}/.github/scripts/render-rpc-contract-summary.py" --check

printf '%s\n' "Docs content contract passed"
