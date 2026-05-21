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

repo_root="$(resolve_repo_root)"
docs_root="${repo_root}/docs"
readme="${repo_root}/README.md"
install_doc="${docs_root}/getting-started/install.md"
backends_doc="${docs_root}/getting-started/backends.md"
quickstart_doc="${docs_root}/getting-started/quickstart.md"
agents_doc="${docs_root}/for-agents/index.md"
index_doc="${docs_root}/index.md"

require_not_contains "$docs_root" '$HOME/.kast/lib/backends' "Docs must use the installer-managed backend path"
require_not_contains "$docs_root" '$(pwd)' "Docs must not rely on unquoted workspace-root command substitution"
require_not_contains "$docs_root" 'IntelliJ plugin-backed runtime' "Docs must use IDEA plugin naming for user-facing backend labels"
require_not_contains "$docs_root" 'Install the IntelliJ plugin' "Docs must use IDEA plugin naming for manual plugin install guidance"
require_not_contains "$readme" '$(pwd)' "README must not rely on unquoted workspace-root command substitution"
require_not_contains "$readme" 'IntelliJ plugin-backed runtime' "README must use IDEA plugin naming for user-facing backend labels"

require_contains "$readme" "brew tap amichne/kast" "README must document the Homebrew tap install"
require_order "$readme" "brew tap amichne/kast" "curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash" "README must promote Homebrew before the shell installer"
require_contains "$readme" "amichne/kast-action@v1" "README must point hosted agents to the GitHub Action install path"
require_contains "$readme" "headless agent installs from internal artifacts or self-contained" "README must point CI/headless users to the contained install docs"
require_contains "$index_doc" "brew install kast" "Docs overview must promote Homebrew for the first local install example"

require_contains "$install_doc" "## Homebrew install" "Install docs must distinguish the Homebrew path"
require_contains "$install_doc" "## Shell installer" "Install docs must keep the shell installer for portable and full-stack flows"
require_order "$install_doc" "## Homebrew install" "## Shell installer" "Install docs must promote Homebrew before the shell installer"
require_contains "$install_doc" "## Developer, CI, and cloud-agent paths" "Install docs must distinguish developer, CI, and cloud-agent flows"
require_contains "$install_doc" "## GitHub Actions and hosted agents" "Install docs must cover action-based hosted agent installs"
require_contains "$install_doc" "amichne/kast-action@v1" "Install docs must document the public Kast GitHub Action"
require_contains "$install_doc" "bundle-url" "Install docs must document mirrored headless bundle input"
require_contains "$install_doc" "bundle-sha256" "Install docs must require checksums for mirrored action installs"
require_contains "$install_doc" "skip-copilot-extension: true" "Install docs must show the conservative enterprise action default"
require_contains "$install_doc" "KAST_INSTALL_SOURCE=action" "Install docs must document action install metadata"
require_contains "$install_doc" 'scripts/headless-agent-install.sh' "Install docs must document the headless agent installer"
require_contains "$install_doc" 'scripts/package-headless-agent-bundle.sh' "Install docs must document bundle packaging"
require_contains "$install_doc" 'scripts/verify-release-assets.sh' "Install docs must document release asset verification"
require_contains "$install_doc" 'KAST_AGENT_CLI_URL' "Install docs must list required headless agent variables"
require_contains "$install_doc" 'KAST_AGENT_BACKEND_URL' "Install docs must list required headless agent variables"
require_contains "$install_doc" 'KAST_SKIP_COPILOT_EXTENSION' "Install docs must list optional headless agent variables"
require_contains "$install_doc" 'KAST_INSTALL_SOURCE' "Install docs must mirror installer environment overrides"
require_contains "$install_doc" '/.kast/backends/current/runtime-libs' "Install docs must use the current backend runtime-libs path"
require_contains "$install_doc" 'push the plugin archive directly' "Install docs must document direct IDE plugin push from the shell installer"

require_contains "$backends_doc" '$HOME/.kast/backends/current/runtime-libs' "Backend docs must use the current backend runtime-libs path"
require_contains "$backends_doc" 'IDEA / Android Studio plugin backend' "Backend docs must use IDEA plugin naming"
require_contains "$quickstart_doc" 'APP_FILE="$PWD/src/main/kotlin/App.kt"' "Quickstart must show a shell-expanded absolute file path"
require_contains "$quickstart_doc" '--workspace-root="$PWD"' "Quickstart must quote the workspace root"
require_contains "$agents_doc" "## Local and hosted agent setup" "Agent docs must separate local and hosted setup"
require_contains "$agents_doc" "GitHub Actions-compatible hosted agent" "Agent docs must document action-based hosted agents"
require_contains "$agents_doc" "Cloud/headless coding agent" "Agent docs must document cloud/headless agent setup"

printf '%s\n' "Docs content contract passed"
