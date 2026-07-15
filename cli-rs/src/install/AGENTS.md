# Install Module Instructions

This directory owns CLI machine install, repair, bundle activation, shell
integration, and managed resource installation. Under
`.agents/adr/0023-signed-idea-plugin-distribution-and-runtime-authority.md`,
JetBrains and the IDE own IDEA plugin trust, installation, and updates. No
forward plugin installer or profile-link authority belongs in this module.

Each part file must own one install contract:
- `types.rs` owns serializable install result and plan types.
- `dispatch.rs` owns top-level install command dispatch.
- `bundle_entrypoint.rs` owns the public activation gateway.
- `agent_guidance.rs` owns `AGENTS.local.md` guidance setup and explicit
  `--context-file` target patching.
- `bundle_source.rs` owns bundle source and tarball extraction.
- `bundle_validation.rs` owns manifest and artifact validation.
- `bundle_install.rs` owns writing the activated bundle to disk.
- `bundle_helpers.rs` owns local path, scratch-dir, copy, and shim helpers.
- `repair.rs` owns doctor/repair state reconciliation.
- `resource_installs.rs` owns the packaged skill install gateway.
- `shell.rs` owns shell profile integration.
- `embedded_resources.rs` owns packaged resource copying and checksums.
- `macos_homebrew_receipt.rs` owns the trusted macOS CLI machine-install
  receipt and the repair-only exact schema-1 recognizer.
- `legacy_idea_plugin_cleanup.rs` owns the 0.13.0-only proof for unlinking
  exactly recognized legacy Homebrew plugin symlinks without replacement.
- `resource_targets.rs` owns default resource target discovery.

Packaged resources, manifests, and generated outputs match the current
contract and report mismatches through typed install or repair reports.

On macOS, a valid `~/Library/Application Support/Kast/homebrew-install.json`
receipt makes Homebrew authoritative for the CLI machine install. Do not
restore `install.json`, global config, or ambient `PATH` as competing CLI binary
authorities. Do not converge the plugin cask, write an IDE profile, mutate
custom repositories, enroll a certificate, or use exact CLI/plugin version
equality as compatibility proof. Repair may retire only exactly recognized
Kast-owned legacy plugin links and receipt fields. Repair must not
use `sudo` or delete unknown state.

Run the installer contract, Homebrew formula contract, authority-cutover and
repair smoke tests, and `:backend-idea:test` when this boundary changes. Run
full Rust formatting, clippy, and tests when shared install code moves.
