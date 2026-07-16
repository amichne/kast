#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

fail() {
  printf 'cli/plugin authority cutover contract: %s\n' "$*" >&2
  exit 1
}

require_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "missing required owner: $path"
}

require_absent() {
  local path="$1"
  [[ ! -e "$path" ]] || fail "retired authority still exists: $path"
}

require_contains() {
  local path="$1"
  local expected="$2"
  grep -Fq -- "$expected" "$path" || fail "missing '$expected' in $path"
}

require_not_contains() {
  local path="$1"
  local unexpected="$2"
  if grep -Fq -- "$unexpected" "$path"; then
    fail "retired authority '$unexpected' remains in $path"
  fi
}

required_files=(
  ".github/scripts/test-macos-installer-contract.sh"
  "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/compatibility/WorkspaceMetadataRevision.kt"
  "backend-idea/src/main/kotlin/io/github/amichne/kast/idea/MacosHomebrewInstallReceipt.kt"
  "backend-idea/src/main/kotlin/io/github/amichne/kast/idea/PluginWorkspaceBootstrap.kt"
  "cli-rs/src/install/legacy_idea_plugin_cleanup.rs"
  "cli-rs/src/install/macos_homebrew_receipt.rs"
  "cli-rs/src/runtime/compatibility.rs"
  "cli-rs/tests/cli_plugin_authority_cutover_smoke.rs"
  "packaging/homebrew/Formula/kast.rb"
  "packaging/homebrew/scripts/test-formulas.py"
  "packaging/jetbrains/runtime-compatibility.json"
)
for path in "${required_files[@]}"; do
  require_file "$path"
done

retired_paths=(
  "packaging/homebrew/Casks/kast-plugin.rb"
  "cli-rs/src/install/homebrew_idea_plugin.rs"
  "cli-rs/src/install/idea_plugin_entrypoint.rs"
  "cli-rs/src/install/jetbrains_profiles.rs"
  "cli-rs/src/install/reporting.rs"
  "cli-rs/tests/machine_plugin_smoke.rs"
  "cli-rs/tests/machine_plugin_repair_smoke.rs"
)
for path in "${retired_paths[@]}"; do
  require_absent "$path"
done

require_contains "install.sh" "repair --for machine --apply"
require_not_contains "install.sh" "developer machine plugin"
require_not_contains "install.sh" "install the IDEA plugin"
require_not_contains "install.sh" "require_jetbrains_ides_closed"
require_not_contains "install.sh" "ps -axo"
require_not_contains "install.sh" "kill -TERM"

require_not_contains "packaging/homebrew/Formula/kast.rb" "PLUGIN_CASK"
require_not_contains "packaging/homebrew/Formula/kast.rb" "developer machine plugin"
require_not_contains "packaging/homebrew/scripts/update-formulas.py" "SHA256_PLUGIN"
require_not_contains "packaging/homebrew/scripts/update-formulas.py" "Casks"
require_contains "packaging/homebrew/scripts/test-formulas.py" "the retired plugin cask must be absent"
require_not_contains "packaging/homebrew/README.md" "brew reinstall --cask"

require_contains ".github/workflows/release.yml" "rm -f homebrew-tap/Casks/kast-plugin.rb"
require_not_contains ".github/workflows/release.yml" "rm -rf homebrew-tap/Casks"
require_not_contains ".github/workflows/release.yml" "SHA256_PLUGIN"
require_contains "scripts/verify-release-state.sh" "Published Homebrew tap retains the retired plugin cask"
require_contains ".github/workflows/ci.yml" "test-cli-plugin-authority-cutover-contract.sh"

require_contains "cli-rs/src/install/macos_homebrew_receipt.rs" "MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION: u32 = 2"
require_contains "cli-rs/src/install/macos_homebrew_receipt.rs" "deny_unknown_fields"
require_not_contains "cli-rs/src/install/macos_homebrew_receipt.rs" "MacosHomebrewPluginReceipt"
require_not_contains "cli-rs/src/install/macos_homebrew_receipt.rs" "pub plugin:"
require_contains "cli-rs/src/install/legacy_idea_plugin_cleanup.rs" "LEGACY_IDEA_PLUGIN_CLEANUP_RELEASE"
require_contains "cli-rs/src/install/legacy_idea_plugin_cleanup.rs" "OwnedLegacySymlink"
require_contains "cli-rs/src/runtime/compatibility.rs" "RuntimeCompatibilityAssessment"
require_contains "cli-rs/src/runtime/compatibility.rs" "MissingCapability"
require_contains "cli-rs/src/self_mgmt.rs" "assess_runtime_compatibility"
require_not_contains "cli-rs/src/self_mgmt.rs" "metadata.plugin_version != current_version"
require_not_contains "cli-rs/src/self_mgmt.rs" "facts.plugin_version != metadata.plugin_version"
require_not_contains "cli-rs/src/cli/install_machine.rs" "IdeaPluginInstallArgs"
require_not_contains "cli-rs/src/cli/command_groups.rs" "MachineCommand::Plugin"
require_not_contains "cli-rs/src/main.rs" "Homebrew-managed IntelliJ plugin"
require_not_contains "cli-rs/src/config/model.rs" "require_installed_plugin"
require_not_contains "cli-rs/src/runtime/idea_launch.rs" "plugin_installed"
require_not_contains "cli-rs/src/runtime/idea_launch.rs" "IDEA_PLUGIN_NOT_INSTALLED"
require_not_contains "cli-rs/src/runtime/descriptors.rs" "developer machine plugin"
require_not_contains "cli-rs/src/runtime/descriptors.rs" "IntelliJ plugin through Homebrew"
require_contains "cli-rs/src/runtime/descriptors.rs" "signed Kast plugin through JetBrains"
require_not_contains "cli-rs/src/output/install.rs" "InstallIdeaPluginResult"

require_contains "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/compatibility/WorkspaceMetadataRevision.kt" "CURRENT = WorkspaceMetadataRevision(3)"
require_contains "packaging/jetbrains/runtime-compatibility.json" '"workspaceMetadataRevision": 3'
require_not_contains "backend-idea/src/main/kotlin/io/github/amichne/kast/idea/MacosHomebrewInstallReceipt.kt" "expectedPluginVersion"
require_not_contains "backend-idea/src/main/kotlin/io/github/amichne/kast/idea/MacosHomebrewInstallReceipt.kt" "caskToken"
require_not_contains "backend-idea/src/main/kotlin/io/github/amichne/kast/idea/MacosHomebrewInstallReceipt.kt" "pluginVersion"
require_not_contains "backend-idea/src/main/kotlin/io/github/amichne/kast/idea/PluginWorkspaceBootstrap.kt" '|  "pluginVersion"'
require_not_contains "backend-idea/src/main/kotlin/io/github/amichne/kast/idea/PluginWorkspaceBootstrap.kt" '|  "cliVersion"'

require_contains "docs/install/macos.md" "Install Plugin from Disk"
require_contains "docs/install/macos.md" "custom plugin repository"
require_not_contains "docs/install/macos.md" "developer machine plugin"
require_not_contains "README.md" "matching plugin"
require_not_contains "docs/use/choose-a-command.md" "CLI and matching JetBrains plugin"
require_contains "docs/use/choose-a-command.md" "signed plugin separately through JetBrains"
require_not_contains "cli-rs/resources/kast-skill/SKILL.md" "developer machine plugin"

bash -n install.sh
bash -n .github/scripts/test-cli-plugin-authority-cutover-contract.sh
.github/scripts/test-macos-installer-contract.sh
python3 packaging/homebrew/scripts/test-formulas.py

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test cli_plugin_authority_cutover_smoke \
  --test runtime_compatibility_metadata_smoke \
  --test ready_repair_smoke

./scripts/ci-gradle-retry.sh ./gradlew \
  :analysis-api:test \
  --tests '*RuntimeCompatibilityMatrixTest*' \
  --tests '*RuntimeCompatibilitySourceContractTest*' \
  :backend-idea:test \
  --tests '*MacosHomebrewInstallReceiptTest*' \
  --tests '*KastProjectOpenProfileAutoInitTest*' \
  --no-daemon

.github/scripts/test-runtime-compatibility-contract.sh
.github/scripts/test-release-workflow-contract.sh
.github/scripts/test-release-asset-verifier.sh
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean

printf 'cli/plugin authority cutover contract: ok\n'
