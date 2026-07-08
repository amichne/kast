# Install Module Instructions

This directory owns machine install, repair, bundle activation, shell
integration, managed resource installation, and IDEA plugin installation.

Each part file must own one install contract:
- `reporting.rs` owns install progress reporting.
- `types.rs` owns serializable install result and plan types.
- `dispatch.rs` owns top-level install command dispatch.
- `bundle_entrypoint.rs` owns the public activation gateway.
- `agent_guidance.rs` owns `AGENTS.local.md` guidance setup and explicit
  `--agents-md` target patching.
- `bundle_source.rs` owns bundle source and tarball extraction.
- `bundle_validation.rs` owns manifest and artifact validation.
- `bundle_install.rs` owns writing the activated bundle to disk.
- `bundle_helpers.rs` owns local path, scratch-dir, copy, and shim helpers.
- `repair.rs` owns doctor/repair state reconciliation.
- `resource_installs.rs` owns the packaged skill install gateway.
- `shell.rs` owns shell profile integration.
- `embedded_resources.rs` owns packaged resource copying and checksums.
- `homebrew_idea_plugin.rs` and `jetbrains_profiles.rs` own IDE plugin flows.
- `resource_targets.rs` owns default resource target discovery.

Packaged resources, manifests, and generated outputs match the current
contract and report mismatches through typed install or repair reports.
