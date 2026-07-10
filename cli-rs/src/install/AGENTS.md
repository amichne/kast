# Install Module Instructions

This directory owns machine install, repair, bundle activation, shell
integration, managed resource installation, and IDEA plugin installation.

Each part file must own one install contract:
- `reporting.rs` owns install progress reporting.
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
- `homebrew_idea_plugin.rs` and `jetbrains_profiles.rs` own IDE plugin flows.
- `macos_homebrew_receipt.rs` owns the trusted macOS machine-install authority
  shared with the IntelliJ plugin.
- `resource_targets.rs` owns default resource target discovery.

Packaged resources, manifests, and generated outputs match the current
contract and report mismatches through typed install or repair reports.

On macOS, a valid `~/Library/Application Support/Kast/homebrew-install.json`
receipt makes Homebrew authoritative. Do not restore `install.json`, global
config, or ambient `PATH` as competing binary authorities. Plugin setup must
converge the version-coupled cask while JetBrains products are closed, then
write the receipt only after links and defaults succeed. Repair may retire only
an exactly recognized, writable legacy shim and manifest; it must not use
`sudo` or delete unknown state.

Run the installer contract, Homebrew formula contract, focused plugin and
repair smoke tests, and `:backend-idea:test` when this boundary changes. Run
full Rust formatting, clippy, and tests when shared install code moves.
